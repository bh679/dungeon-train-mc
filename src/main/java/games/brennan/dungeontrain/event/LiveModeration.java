package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.compat.vista.TvItems;
import games.brennan.dungeontrain.discord.WorldInfoReporter;
import games.brennan.dungeontrain.moderation.LiveModerationLadder;
import games.brennan.dungeontrain.moderation.LiveModerationLadder.Action;
import games.brennan.dungeontrain.moderation.LiveModerationLadder.Verdict;
import games.brennan.dungeontrain.moderation.LiveModerationScope;
import games.brennan.dungeontrain.moderation.LiveWordList;
import games.brennan.dungeontrain.net.LiveModerationStrikePacket;
import games.brennan.dungeontrain.net.relay.LiveModerationClient;
import games.brennan.dungeontrain.net.relay.LiveWordListFetcher;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import games.brennan.dungeontrain.player.ServerLiveStatus;
import games.brennan.dungeontrain.util.PresenceLine;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Server half of the Live Eye text filter: who has struck out, and what it costs them.
 *
 * <p>The relay ({@code livestrikes.js}) owns the ladder. This class mirrors each online player's
 * verdict ({@link #isBanned}, {@link #isMuted}), reports every masked word as a strike through the
 * durable {@link RelayOutbox}, and acts on the relay's answer: a {@code mute} stops the player's chat
 * ({@link LiveChatCensorEvents}) until it lapses, and ends their stream when the relay says so (a slur);
 * a {@code ban} burns the Live Eye, ends the stream ({@link LiveFeedEvents#endForModeration}) and marks
 * the player so a Live Eye can't be worn or looted ({@link LiveEyeLootResolver}) until it lapses.</p>
 *
 * <p>A Free Play run reports no strikes: it never goes live, and Free Play promises not to log.
 * The words are still masked.</p>
 *
 * <p>The player hears a warning the moment a word is masked, counted locally against the ladder the
 * relay published ({@link LiveWordList#ladder()}); the relay's reply then does the acting. A relay
 * outage therefore costs nothing but the warning text being a guess. Strikes are rate-limited to one
 * per player per {@link #STRIKE_GAP_MS}: the client and the server both see the same chat line.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveModeration {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One outburst, one strike — the client's as-typed report and the server's on-send pass overlap. */
    static final long STRIKE_GAP_MS = 10_000L;

    private static final String KEY = "chat.dungeontrain.live.";

    /** A verdict and when it arrived, so a timed ban is measured from arrival, not from the relay's clock. */
    record Cached(Verdict verdict, long receivedAtMs) {}

    private static final Map<UUID, Cached> VERDICTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_STRIKE_MS = new ConcurrentHashMap<>();

    private LiveModeration() {}

    /** Register the outbox response handler. Called once at mod setup. */
    public static void register() {
        RelayOutbox.get().onResponse(LiveModerationClient.STRIKE_PATH, LiveModeration::onStrikeResponse);
        RelayOutbox.get().onResponse(LiveModerationClient.APPEAL_PATH, LiveModeration::onAppealResponse);
    }

    // --- state ------------------------------------------------------------------------------------

    /** Whether {@code player} may not wear or loot a Live Eye right now. */
    public static boolean isBanned(UUID player) {
        Cached c = VERDICTS.get(player);
        return c != null && !c.verdict().mayStream(System.currentTimeMillis(), c.receivedAtMs());
    }

    /** Seconds of ban left for {@code player}; 0 when not banned, {@link Long#MAX_VALUE} when permanent. */
    public static long banRemainingSec(UUID player) {
        Cached c = VERDICTS.get(player);
        return c == null ? 0 : c.verdict().remainingAt(System.currentTimeMillis(), c.receivedAtMs());
    }

    /** Whether {@code player}'s chat is muted right now. */
    public static boolean isMuted(UUID player) {
        return muteRemainingSec(player) > 0;
    }

    /** Seconds of chat mute left for {@code player}; 0 when not muted, {@link Long#MAX_VALUE} when permanent. */
    public static long muteRemainingSec(UUID player) {
        Cached c = VERDICTS.get(player);
        return c == null ? 0 : c.verdict().muteRemainingAt(System.currentTimeMillis(), c.receivedAtMs());
    }

    /** Whether {@code player} has a penalty to appeal: a chat mute or a Live Eye ban. */
    public static boolean isPenalised(UUID player) {
        return isMuted(player) || isBanned(player);
    }

    static void apply(UUID player, Verdict v, long nowMs) {
        VERDICTS.put(player, new Cached(v, nowMs));
    }

    // --- lifecycle --------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        LiveWordListFetcher.ensureFetched();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) refresh(player);
    }

    /** Ask the relay for {@code player}'s verdict again (login, and each refused Live Eye while banned). */
    public static void refresh(ServerPlayer player) {
        refresh(player, p -> { });
    }

    /**
     * {@link #refresh(ServerPlayer)}, then {@code then} on the server thread once the relay has answered
     * or failed to — only if the player is still online. Used where the relay knows of a ban the server
     * may not (its login fetch can miss): the Live Eye's burn line asks first.
     */
    public static void refresh(ServerPlayer player, Consumer<ServerPlayer> then) {
        refresh(player, then, then);
    }

    /** {@link #refresh(ServerPlayer, Consumer)}, with {@code missed} instead when the relay gave no good answer. */
    public static void refresh(ServerPlayer player, Consumer<ServerPlayer> answered, Consumer<ServerPlayer> missed) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        UUID uuid = player.getUUID();
        AtomicBoolean done = new AtomicBoolean();
        Consumer<Consumer<ServerPlayer>> finish = next -> {
            if (!done.compareAndSet(false, true)) return;
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            if (online != null) next.accept(online);
        };
        LiveModerationClient.fetchStatus(uuid,
            v -> server.execute(() -> {
                apply(uuid, v, System.currentTimeMillis());
                finish.accept(answered);
            }),
            () -> server.execute(() -> finish.accept(missed)));
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_STRIKE_MS.remove(event.getEntity().getUUID());
        LAST_LINK_WARN_MS.remove(event.getEntity().getUUID());
        LAST_MUTE_REMINDER_MS.remove(event.getEntity().getUUID());
        // The verdict stays cached: a relog within the session must not forget a ban.
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        VERDICTS.clear();
        LAST_STRIKE_MS.clear();
        LAST_MUTE_REMINDER_MS.clear();
    }

    // --- links and phone numbers (hidden, never a strike) ---------------------------------------

    private static final Map<UUID, Long> LAST_LINK_WARN_MS = new ConcurrentHashMap<>();
    static final long LINK_WARN_GAP_MS = 10_000L;

    /** Tell {@code player} a link or phone number was hidden — at most once per 10 s, no strike. */
    public static void warnLinkHidden(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = LAST_LINK_WARN_MS.get(player.getUUID());
        if (last != null && now - last < LINK_WARN_GAP_MS) return;
        LAST_LINK_WARN_MS.put(player.getUUID(), now);
        player.sendSystemMessage(Component.translatable(KEY + "link_hidden").withStyle(ChatFormatting.GRAY));
    }

    // --- strikes ----------------------------------------------------------------------------------

    /** The server itself masked a word in {@code player}'s chat line or book; {@code text} is the line as typed. */
    public static void strike(ServerPlayer player, String source, List<String> hits, String text) {
        record(player, source, hits, text);
    }

    /** The player's client says it masked a word as they typed. Counted while anyone is live, or they wear a TV. */
    public static void strikeFromClient(ServerPlayer player, LiveModerationStrikePacket.Source source, String text, List<String> hits) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        boolean wearingTv = TvItems.isTv(player.getItemBySlot(EquipmentSlot.HEAD));
        if (!LiveModerationScope.clientStrikeActive(ServerLiveStatus.isAnyoneLive(server), wearingTv)) return;
        record(player, source.relayName(), hits, text);
    }

    private static void record(ServerPlayer player, String source, List<String> hits, String text) {
        if (skipsStrikes(DungeonTrain.isDevBuild(), RunIntegrity.isCheated(player))) return;
        long now = System.currentTimeMillis();
        UUID uuid = player.getUUID();
        Long last = LAST_STRIKE_MS.get(uuid);
        if (last != null && now - last < STRIKE_GAP_MS) return;
        LAST_STRIKE_MS.put(uuid, now);

        Cached c = VERDICTS.get(uuid);
        int strikesAfter = (c == null ? 0 : c.verdict().strikes()) + 1;
        LiveWordList.Ladder ladder = LiveWordList.ladder();
        // While a penalty runs the relay only logs the line; a "warning N of 3" would be a lie.
        Component line = isPenalised(uuid) ? null : warning(source, strikesAfter, ladder);
        if (line != null) player.sendSystemMessage(line);
        LOGGER.info("[DungeonTrain] live moderation: {} masked in {}'s {} (local strike {} of {})",
            hits.size(), player.getGameProfile().getName(), source, strikesAfter, ladder.removeAtStrike());

        try {
            RelayOutbox.get().enqueue(LiveModerationClient.STRIKE_PATH,
                LiveModerationClient.strikeBody(uuid, player.getGameProfile().getName(), source, hits, text));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] live moderation strike could not be queued: {}", t.toString());
        }
    }

    /**
     * Whether a strike goes unreported: a Free Play run (dev builds exempt, the same carve-out as
     * {@link LiveFeedEvents#streamBlocked(boolean, boolean)}, so the filter can be tested in creative). Pure.
     */
    static boolean skipsStrikes(boolean devBuild, boolean freePlay) {
        return !devBuild && freePlay;
    }

    /**
     * The line a player hears when a word is masked: which warning this is, out of how many. On the
     * strike that uses the last one up there is no warning — the Live Eye's burn line says it all
     * ({@code chat.dungeontrain.live.burns_moderated}), so null here.
     */
    @Nullable
    static Component warning(String source, int strikesAfter, LiveWordList.Ladder ladder) {
        if (LiveModerationLadder.decideLocally(strikesAfter, ladder) == Action.REMOVE) return null;
        boolean book = source != null && source.startsWith("book");
        int shown = Math.min(strikesAfter, ladder.warningsBeforeRemoval());
        return Component.translatable(KEY + (book ? "censored_book" : "censored"),
            shown, ladder.warningsBeforeRemoval()).withStyle(ChatFormatting.RED);
    }

    // --- the relay's answer -----------------------------------------------------------------------

    static void onStrikeResponse(String requestBody, int status, String responseBody) {
        if (status / 100 != 2) return;
        Verdict v = LiveModerationLadder.parse(responseBody);
        if (v == null) return;
        UUID player = LiveModerationClient.playerOf(requestBody);
        if (player == null) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        server.execute(() -> {
            try {
                apply(player, v, System.currentTimeMillis());
                ServerPlayer online = server.getPlayerList().getPlayer(player);
                if (online == null) return;
                switch (v.action()) {
                    case MUTE -> {
                        if (v.endStream()) LiveFeedEvents.endStreamForModeration(online);
                        online.sendSystemMessage(mutedLine(online, true));
                    }
                    case REMOVE -> LiveFeedEvents.endForModeration(online, false);
                    case BAN -> {
                        LiveFeedEvents.endForModeration(online, true); // the burn line carries the ban
                        if (isMuted(player)) online.sendSystemMessage(mutedLine(online, false));
                    }
                    default -> { }
                }
            } catch (Throwable t) {
                LOGGER.debug("[DungeonTrain] live moderation verdict could not be applied: {}", t.toString());
            }
        });
    }

    /** The logged lines behind {@code player}'s ban, newest first; empty when not banned or unknown. */
    public static List<LiveModerationLadder.Evidence> evidenceFor(UUID player) {
        Cached c = VERDICTS.get(player);
        return c == null ? List.of() : c.verdict().evidence();
    }

    /**
     * The burn line of a banned player's Live Eye: "The Live Eye burns away. You can't wear another for
     * N days." (or "…can no longer wear one."), then the lines that earned the ban — each masked, with
     * a clickable [Appeal] that runs {@code /liveappeal <n>} — and the appeal hint.
     */
    public static Component bannedBurnLine(ServerPlayer player) {
        long left = banRemainingSec(player.getUUID());
        String key = bannedBurnKey(left);
        MutableComponent out;
        if (key.endsWith("burns_banned")) {
            String locale = WorldInfoReporter.clientLanguage(player);
            // Rounded up to the next hour: "7 days" a minute into a week-long ban, not "6 days".
            out = Component.translatable(key,
                PresenceLine.agoComponent(locale, Duration.ofSeconds(left + 3599L))).withStyle(ChatFormatting.RED);
        } else {
            out = Component.translatable(key).withStyle(ChatFormatting.RED);
        }
        List<LiveModerationLadder.Evidence> evidence = evidenceFor(player.getUUID());
        if (!evidence.isEmpty()) {
            out.append("\n").append(Component.translatable(KEY + "banned_for").withStyle(ChatFormatting.GRAY));
            out.append(evidenceLines(evidence));
        }
        return out.append("\n").append(Component.translatable(KEY + "appeal_hint").withStyle(ChatFormatting.GRAY));
    }

    /**
     * The line a player hears when their chat is muted: "Your chat is muted for N hours." (or
     * "...permanently."), then, with {@code withEvidence}, the lines that earned it with [Appeal] on each,
     * and the appeal hint.
     */
    public static Component mutedLine(ServerPlayer player, boolean withEvidence) {
        MutableComponent out = muteLength(player, KEY + "muted", KEY + "muted_permanent");
        List<LiveModerationLadder.Evidence> evidence = withEvidence ? evidenceFor(player.getUUID()) : List.of();
        if (!evidence.isEmpty()) {
            out.append("\n").append(Component.translatable(KEY + "muted_for").withStyle(ChatFormatting.GRAY));
            out.append(evidenceLines(evidence));
        }
        if (withEvidence) out.append("\n").append(Component.translatable(KEY + "appeal_hint").withStyle(ChatFormatting.GRAY));
        return out;
    }

    // --- chat while muted -------------------------------------------------------------------------

    private static final Map<UUID, Long> LAST_MUTE_REMINDER_MS = new ConcurrentHashMap<>();
    static final long MUTE_REMINDER_GAP_MS = 10_000L;

    /** Tell a muted {@code player} why their line went nowhere: at most once per 10 s. */
    public static void remindMuted(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = LAST_MUTE_REMINDER_MS.get(player.getUUID());
        if (last != null && now - last < MUTE_REMINDER_GAP_MS) return;
        LAST_MUTE_REMINDER_MS.put(player.getUUID(), now);
        player.sendSystemMessage(muteLength(player, KEY + "muted_reminder", KEY + "muted_reminder_permanent"));
    }

    /** {@code timedKey} with the mute's length (rounded up to the minute), or {@code permanentKey}. */
    private static MutableComponent muteLength(ServerPlayer player, String timedKey, String permanentKey) {
        long left = muteRemainingSec(player.getUUID());
        if (left == Long.MAX_VALUE) return Component.translatable(permanentKey).withStyle(ChatFormatting.RED);
        String locale = WorldInfoReporter.clientLanguage(player);
        // Rounded up: "15 minutes" a second into a 15-minute mute, not "14 minutes".
        return Component.translatable(timedKey,
            PresenceLine.agoComponent(locale, Duration.ofSeconds(left + 59L))).withStyle(ChatFormatting.RED);
    }

    /**
     * The burn line's key for {@code left} seconds of ban. 0 means the relay refused the Live Eye but
     * the server holds no ban for them (its status fetch missed): that line names no length, rather
     * than making one up. Pure.
     */
    static String bannedBurnKey(long left) {
        if (left <= 0) return KEY + "burns_banned_unknown";
        if (left == Long.MAX_VALUE) return KEY + "burns_banned_permanent";
        return KEY + "burns_banned";
    }

    /**
     * One line per logged strike: "1. you **** there  [Appeal]". The text is shown masked — the
     * player knows what they typed, and the word must not reappear on anyone's screen. Clicking
     * [Appeal] runs {@code /liveappeal <n>}.
     */
    public static Component evidenceLines(List<LiveModerationLadder.Evidence> evidence) {
        MutableComponent out = Component.empty();
        var words = LiveWordList.effective();
        for (int i = 0; i < evidence.size(); i++) {
            final int n = i + 1;
            LiveModerationLadder.Evidence e = evidence.get(i);
            String shown = games.brennan.dungeontrain.moderation.TextCensor.censor(e.text(), words).text();
            if (shown.isBlank()) shown = String.join(", ", e.terms());
            out.append("\n").append(Component.literal(n + ". ").withStyle(ChatFormatting.DARK_GRAY))
               .append(Component.literal(shown).withStyle(ChatFormatting.GRAY))
               .append(" ")
               .append(Component.translatable(KEY + "appeal_link").withStyle(s -> s.withColor(ChatFormatting.AQUA)
                   .withUnderlined(true)
                   .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/liveappeal " + n))
                   .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(KEY + "appeal_link_hover")))));
        }
        return out;
    }

    // --- appeals ----------------------------------------------------------------------------------

    /** What the relay said to a {@code /liveappeal}, turned into the chat line the player hears. */
    static void onAppealResponse(String requestBody, int status, String responseBody) {
        if (status < 0) return; // never delivered; the outbox retries
        UUID player = LiveModerationClient.playerOf(requestBody);
        if (player == null) return;
        String key = appealLineKey(status, responseBody);
        if (key == null) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        server.execute(() -> {
            ServerPlayer online = server.getPlayerList().getPlayer(player);
            if (online != null) online.sendSystemMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY));
        });
    }

    /** The lang key for a relay answer to an appeal; null when there is nothing to say. Pure. */
    static String appealLineKey(int status, String responseBody) {
        if (status / 100 == 2) return "command.dungeontrain.live_appeal.received";
        String error = "";
        try {
            var o = com.google.gson.JsonParser.parseString(responseBody == null ? "" : responseBody).getAsJsonObject();
            if (o.has("error")) error = o.get("error").getAsString();
        } catch (Throwable t) {
            // fall through to the generic line
        }
        return switch (error) {
            case "not_banned" -> "command.dungeontrain.live_appeal.not_penalised";
            case "appeal_pending" -> "command.dungeontrain.live_appeal.pending";
            case "appeal_denied" -> "command.dungeontrain.live_appeal.denied";
            default -> "command.dungeontrain.live_appeal.failed";
        };
    }
}
