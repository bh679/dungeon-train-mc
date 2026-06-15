package games.brennan.dungeontrain.editor;

import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends a friendly onboarding message in chat 2.2 seconds after a player first
 * enters the Dungeon Train editor each play session.
 *
 * <p>"Entered the editor" reuses the three command paths that already fire the
 * {@code EDITOR_ACTION "entered_editor"} advancement trigger (category, carriage,
 * and tunnel enters in {@code EditorCommand}); they all route through
 * {@code EditorCommand.markEnteredEditor}, which calls {@link #showOnEnter}.</p>
 *
 * <p>The 2.2s delay is scheduled like {@code CinematicIntroService}: an entry is
 * parked with an overworld game-tick deadline and sent by {@link #tick}, which is
 * pumped once per server tick from {@code PlayerJoinEvents.onLevelTick}.
 * "Once per play session" is gated by an in-memory UUID set cleared on logout
 * (via {@link #forget}), so a relog re-shows the welcome.</p>
 */
public final class EditorWelcome {

    private EditorWelcome() {}

    /** Delay before the welcome lands: 2.2 seconds at 20 ticks/second = 44 ticks. */
    private static final long WELCOME_DELAY_TICKS = 44L;

    /** Players already welcomed (or currently scheduled) this session; gates "once per session". */
    private static final Set<UUID> WELCOMED = ConcurrentHashMap.newKeySet();

    /** Player UUID → overworld game-tick deadline at which the scheduled welcome is sent. */
    private static final Map<UUID, Long> PENDING = new ConcurrentHashMap<>();

    /**
     * The first time {@code player} enters the editor this session, schedule the
     * welcome to land {@link #WELCOME_DELAY_TICKS} ticks (≈2.2s) later; a no-op on
     * every later entry until they log out and back in.
     */
    public static void showOnEnter(ServerPlayer player) {
        if (!WELCOMED.add(player.getUUID())) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        long deadline = server.overworld().getGameTime() + WELCOME_DELAY_TICKS;
        PENDING.put(player.getUUID(), deadline);
    }

    /**
     * Per-tick pump (hook from the overworld {@code LevelTickEvent.Post}, next to
     * {@code CinematicIntroService.tick}). Sends each scheduled welcome once its
     * 2.2s delay has elapsed.
     */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        long now = server.overworld().getGameTime();
        PENDING.entrySet().removeIf(e -> {
            if (now < e.getValue()) return false;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null) p.sendSystemMessage(buildWelcome());
            return true;
        });
    }

    /** Drop tracking on logout (do not touch the now-offline entity). */
    public static void forget(UUID playerId) {
        WELCOMED.remove(playerId);
        PENDING.remove(playerId);
    }

    /**
     * The multi-line welcome, sent as a single chat entry. "@brennanhatton" is a
     * clickable mention that pre-fills the chat box (relayed to Brennan on Discord
     * by the bundled Discord Presence mod) — styled like the clickable link in
     * {@code ExportCommand.formatSuccess}. When the bundled Discord Presence mod knows
     * Brennan's presence, a trailing {@link #buildPresenceLine() "last seen online"}
     * line is appended.
     */
    private static Component buildWelcome() {
        Component mention = Component.literal("@brennanhatton")
            .withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "@brennanhatton "))
                .withHoverEvent(new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    Component.literal("Click to message Brennan — your message reaches him on Discord"))));

        MutableComponent welcome = Component.empty()
            .append(Component.literal("Welcome to the Dungeon Train Editor!")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(Component.literal("\nThis is a full editor for everything in the train."))
            .append(Component.literal("\nFrom train carriage templates to custom loot tables editor."))
            .append(Component.literal("\nIf you have any questions - message Brennan in here with "))
            .append(mention)
            .append(Component.literal(
                "\nHe will be very enthusiastic that you're using the editor and do what he can to support you!"));

        Component presence = buildPresenceLine();
        if (presence != null) {
            welcome.append(presence);
        }
        return welcome;
    }

    /**
     * The trailing presence line — "Brennan is online on Discord right now!" or "Brennan was last
     * seen online {@literal <}duration{@literal >} ago." — read from the bundled Discord Presence mod's
     * query seam ({@link DiscordService#isDiscordUserOnline}/{@link DiscordService#lastSeenOnline}), or
     * {@code null} when presence is unknown so the welcome omits the line entirely.
     *
     * <p>Both seam methods are absent-safe and return empty on DT's default relay-mode (DP holds no
     * local gateway there) — only a direct-bot operator with the Presence intent populates them. So in
     * the common case this returns {@code null} and the welcome reads exactly as it did before.</p>
     */
    private static Component buildPresenceLine() {
        DiscordService dp = DiscordService.get();
        if (dp.isDiscordUserOnline(DungeonTrain.BRENNAN_DISCORD_ID).orElse(false)) {
            return Component.literal("\nBrennan is online on Discord right now!")
                .withStyle(ChatFormatting.GREEN);
        }
        Optional<Instant> seen = dp.lastSeenOnline(DungeonTrain.BRENNAN_DISCORD_ID);
        if (seen.isPresent()) {
            String ago = humanizeAgo(Duration.between(seen.get(), Instant.now()));
            return Component.literal("\nBrennan was last seen online " + ago + " ago.")
                .withStyle(ChatFormatting.GRAY);
        }
        return null; // presence unknown (relay-mode default) — omit the line
    }

    /**
     * Formats a positive elapsed {@link Duration} as a coarse human phrase — "5 minutes", "1 hour",
     * "3 days" — picking the largest whole unit (second → minute → hour → day) and pluralising. A zero
     * or negative duration (clock skew) clamps to "0 seconds". Pure + package-private for unit tests.
     */
    static String humanizeAgo(Duration d) {
        long secs = Math.max(0L, d.getSeconds());
        if (secs < 60L) return plural(secs, "second");
        long mins = secs / 60L;
        if (mins < 60L) return plural(mins, "minute");
        long hours = mins / 60L;
        if (hours < 24L) return plural(hours, "hour");
        return plural(hours / 24L, "day");
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1L ? "" : "s");
    }
}
