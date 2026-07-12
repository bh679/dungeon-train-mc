package games.brennan.dungeontrain.event;

import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.echo.RemoteEchoEncounters;
import games.brennan.dungeontrain.util.PresenceLine;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * When a player types {@code @dev} or {@code @brennanhatton} in chat, privately tells the SENDER how
 * recently Brennan was last seen online on Discord — but only when he is online now or was seen within
 * {@link PresenceLine#MENTION_PRESENCE_WINDOW}; otherwise stays silent. This sets the tagging player's
 * expectation of a timely reply without changing anything else: the line is the only addition.
 *
 * <p>The presence is read at tag-time, but the reply is held back {@link #REPLY_DELAY_TICKS} (~0.5 s) so
 * it lands as a beat <em>after</em> the player's own message rather than on top of it — the same
 * deliberate pacing {@code EditorWelcome} uses. Deferral goes through a tick-queue drained on the
 * overworld {@link LevelTickEvent.Post}, the codebase idiom for timed chat lines. Each player is also
 * rate-limited to one reply per {@link #REPLY_COOLDOWN_TICKS} (5 min) so spamming the tag can't flood
 * them; a silent (presence-unknown) tag doesn't consume that cooldown.</p>
 *
 * <p>Observe-only — never cancels or edits the message — so the bundled Discord Presence mod's own
 * {@code ServerChatEvent} listener still relays the line and rewrites the {@code @}-token to a real
 * Discord ping. Detection mirrors that mod's matcher exactly (case-insensitive substring over the shared
 * {@link DungeonTrain#MENTION_TOKENS}) so this reply appears precisely when the real ping fires.</p>
 *
 * <p>No {@code value = Dist}: like the relay listener it registers on both sides, so the integrated
 * server (singleplayer) replies too. The presence read is a synchronous in-memory lookup, and the chat,
 * tick, and shutdown handlers all run on the server thread, so the queue and cooldown map are touched
 * single-threaded.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class MentionPresenceEvents {

    private MentionPresenceEvents() {}

    /** Beat before the reply shows, so it follows the player's own line instead of landing on top of it. */
    private static final long REPLY_DELAY_TICKS = 10L; // ~0.5 s at 20 TPS

    /** Per-player rate limit: at most one reply per this window, so spamming the tag can't flood a player. */
    private static final long REPLY_COOLDOWN_TICKS = 5L * 60L * 20L; // 5 min at 20 TPS = 6000 ticks

    /** A presence reply waiting out its delay: deliver {@code line} to {@code playerId} once {@code dueTick} arrives. */
    private record Pending(long dueTick, UUID playerId, Component line) {}

    /** Server-thread only (chat + overworld tick both run there); concurrent type is cheap insurance. */
    private static final Queue<Pending> PENDING = new ConcurrentLinkedQueue<>();

    /** Last gameTime tick a reply was enqueued, per player — anchors the per-player 5-min cooldown. */
    private static final Map<UUID, Long> LAST_REPLY_TICK = new ConcurrentHashMap<>();

    /**
     * Converted off the NeoForge bus (Stage 2a) — now a plain
     * {@link games.brennan.dungeontrain.platform.event.DtServerChatCallback}
     * registered via {@code DtEvents.SERVER_CHAT} (see {@code NeoForgeServerEvents}),
     * fired by {@code NeoForgeChatBridge}. Logic unchanged. {@code message} is the
     * decorated broadcast component (unused here — this listener is observe-only).
     */
    public static void onServerChat(ServerPlayer player, String rawText, Component message) {
        boolean mentionsDev = mentionsBrennan(rawText);
        // Feed every chat line to the echo journal (it logs a beat only when an echo is around);
        // a @dev mention here also counts as dev contact for the chat-contents privacy guard.
        RemoteEchoEncounters.onPrimaryPlayerChat(player, rawText, mentionsDev);

        // Dev-message consent: any chat line slides the 20-minute window once consent is granted; a
        // @Dev typed while a Developer message is held accepts consent, flushing it into in-game chat.
        ServerPlayer chatter = player;
        DevMessageConsent.onPlayerChatted(chatter);
        if (mentionsDev && DevMessageConsent.hasPending(chatter.getUUID())) {
            DevMessageConsent.onConsentAccepted(chatter);
        }

        if (!mentionsDev) {
            return;
        }
        // "Summon The Creator": tagging @dev / @brennanhatton earns the advancement,
        // whether or not Brennan is online (the presence reply below is separate and
        // may stay silent). Idempotent, so repeat tags are harmless.
        AchievementEvents.notifyTaggedCreator(chatter);
        ServerPlayer sender = player;
        MinecraftServer server = sender.getServer();
        if (server == null) {
            return;
        }
        DiscordService dp = DiscordService.get();
        Component line = PresenceLine.recentLine(
                dp.isDiscordUserOnline(DungeonTrain.BRENNAN_DISCORD_ID),
                dp.lastSeenOnline(DungeonTrain.BRENNAN_DISCORD_ID),
                Instant.now());
        if (line == null) {
            return; // silent (offline > 30 min / unknown) — don't consume the per-player cooldown
        }
        UUID playerId = sender.getUUID();
        long now = server.overworld().getGameTime();
        Long last = LAST_REPLY_TICK.get(playerId);
        if (last != null && now - last < REPLY_COOLDOWN_TICKS) {
            return; // rate-limited: already replied to this player within the last 5 minutes
        }
        // Stamp the cooldown at tag-time (also dedupes a second tag arriving during the 0.5 s delay).
        LAST_REPLY_TICK.put(playerId, now);
        PENDING.add(new Pending(now + REPLY_DELAY_TICKS, playerId, line));
    }

    /** Once-per-server-tick drain, anchored on the overworld so it runs once rather than per dimension. */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        MinecraftServer server = level.getServer();
        long now = server.overworld().getGameTime();
        PENDING.removeIf(p -> {
            if (now < p.dueTick()) {
                return false; // not yet due
            }
            ServerPlayer target = server.getPlayerList().getPlayer(p.playerId());
            if (target != null) {
                target.sendSystemMessage(p.line()); // sender only — the public broadcast is untouched
            }
            return true; // drop whether delivered or the player has since logged out
        });
    }

    /** Drop pending replies and cooldowns on shutdown so nothing leaks into the next world (gameTime resets). */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PENDING.clear();
        LAST_REPLY_TICK.clear();
    }

    /**
     * Whether {@code text} contains any mention token, by the bundled Discord Presence mod's exact rule:
     * case-insensitive substring (NOT word-boundary). Mirroring it keeps this reply in lock-step with the
     * real Discord ping — do not tighten to word-boundary or the two desync.
     */
    static boolean mentionsBrennan(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String token : DungeonTrain.MENTION_TOKENS) {
            if (lower.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
