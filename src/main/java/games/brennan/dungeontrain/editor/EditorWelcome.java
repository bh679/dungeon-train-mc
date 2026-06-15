package games.brennan.dungeontrain.editor;

import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Sends a friendly onboarding message in chat after a player first enters the Dungeon Train editor
 * each play session. The first line lands ~2.2 seconds after entering; the remaining lines then
 * arrive one at a time, ~0.7 seconds apart, so the welcome reads like someone typing rather than a
 * wall of text.
 *
 * <p>"Entered the editor" reuses the three command paths that already fire the
 * {@code EDITOR_ACTION "entered_editor"} advancement trigger (category, carriage,
 * and tunnel enters in {@code EditorCommand}); they all route through
 * {@code EditorCommand.markEnteredEditor}, which calls {@link #showOnEnter}.</p>
 *
 * <p>Scheduling is tick-based like {@code CinematicIntroService}: each player's remaining lines are
 * parked in a queue with a "next line" overworld-game-tick deadline, drained one line per due tick by
 * {@link #tick} (pumped once per server tick from {@code PlayerJoinEvents.onLevelTick}). "Once per
 * play session" is gated by an in-memory UUID set cleared on logout (via {@link #forget}), so a relog
 * re-shows the welcome.</p>
 */
public final class EditorWelcome {

    private EditorWelcome() {}

    /** Delay before the first line lands: 2.2 seconds at 20 ticks/second = 44 ticks. */
    private static final long WELCOME_DELAY_TICKS = 44L;

    /** Gap between consecutive lines: 0.7 seconds at 20 ticks/second = 14 ticks. */
    private static final long LINE_GAP_TICKS = 14L;

    /** Players already welcomed (or currently scheduled) this session; gates "once per session". */
    private static final Set<UUID> WELCOMED = ConcurrentHashMap.newKeySet();

    /** Player UUID → the remaining welcome lines to send (built lazily so the presence line is fresh). */
    private static final Map<UUID, Deque<Supplier<Component>>> PENDING = new ConcurrentHashMap<>();

    /** Player UUID → overworld game-tick at which the next queued line should be sent. */
    private static final Map<UUID, Long> NEXT_SEND = new ConcurrentHashMap<>();

    /**
     * The first time {@code player} enters the editor this session, queue the welcome lines and
     * schedule the first to land {@link #WELCOME_DELAY_TICKS} ticks (≈2.2s) later; a no-op on every
     * later entry until they log out and back in.
     */
    public static void showOnEnter(ServerPlayer player) {
        if (!WELCOMED.add(player.getUUID())) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        PENDING.put(player.getUUID(), new ArrayDeque<>(welcomeLines()));
        NEXT_SEND.put(player.getUUID(), server.overworld().getGameTime() + WELCOME_DELAY_TICKS);
    }

    /**
     * Per-tick pump (hook from the overworld {@code LevelTickEvent.Post}, next to
     * {@code CinematicIntroService.tick}). Sends at most one queued line per player whose next-line
     * deadline has elapsed, then re-arms the deadline {@link #LINE_GAP_TICKS} ticks out until the
     * player's queue drains.
     */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        long now = server.overworld().getGameTime();
        PENDING.entrySet().removeIf(e -> {
            UUID id = e.getKey();
            Long due = NEXT_SEND.get(id);
            if (due == null || now < due) return false; // not this player's turn yet
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) { NEXT_SEND.remove(id); return true; } // logged off mid-welcome
            Deque<Supplier<Component>> lines = e.getValue();
            // Pull the next renderable line; a null (e.g. unknown presence) is skipped, not sent blank.
            Component line = null;
            while (line == null && !lines.isEmpty()) {
                line = lines.poll().get();
            }
            if (line != null) p.sendSystemMessage(line);
            if (lines.isEmpty()) { NEXT_SEND.remove(id); return true; } // all lines delivered
            NEXT_SEND.put(id, now + LINE_GAP_TICKS);
            return false;
        });
    }

    /** Drop tracking on logout (do not touch the now-offline entity). */
    public static void forget(UUID playerId) {
        WELCOMED.remove(playerId);
        PENDING.remove(playerId);
        NEXT_SEND.remove(playerId);
    }

    /**
     * The welcome, one line per entry, each sent as its own chat message {@link #LINE_GAP_TICKS} ticks
     * after the previous. Suppliers (not pre-built Components) so the trailing presence line is read
     * fresh at send time — and {@link #buildPresenceLine() omitted} (returns {@code null}) when
     * Brennan's Discord presence is unknown.
     */
    private static List<Supplier<Component>> welcomeLines() {
        return List.of(
            () -> Component.literal("Welcome to the Dungeon Train Editor!")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
            () -> Component.literal("This is a full editor for everything in the train."),
            () -> Component.literal("From train carriage templates to custom loot tables editor."),
            () -> Component.literal("If you have any questions - message Brennan in here with ")
                .append(mentionTag()),
            () -> Component.literal(
                "He will be very enthusiastic that you're using the editor and do what he can to support you!"),
            EditorWelcome::buildPresenceLine);
    }

    /**
     * "@brennanhatton" — a clickable mention that pre-fills the chat box (relayed to Brennan on Discord
     * by the bundled Discord Presence mod), styled like the clickable link in
     * {@code ExportCommand.formatSuccess}.
     */
    private static Component mentionTag() {
        return Component.literal("@brennanhatton")
            .withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "@brennanhatton "))
                .withHoverEvent(new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    Component.literal("Click to message Brennan — your message reaches him on Discord"))));
    }

    /**
     * The trailing presence line — "Brennan is online on Discord right now!" or "Brennan was last
     * seen online {@literal <}duration{@literal >} ago." — read from the bundled Discord Presence mod's
     * query seam ({@link DiscordService#isDiscordUserOnline}/{@link DiscordService#lastSeenOnline}), or
     * {@code null} when presence is unknown so the welcome omits the line entirely.
     */
    private static Component buildPresenceLine() {
        DiscordService dp = DiscordService.get();
        if (dp.isDiscordUserOnline(DungeonTrain.BRENNAN_DISCORD_ID).orElse(false)) {
            return Component.literal("Brennan is online on Discord right now!")
                .withStyle(ChatFormatting.GREEN);
        }
        Optional<Instant> seen = dp.lastSeenOnline(DungeonTrain.BRENNAN_DISCORD_ID);
        if (seen.isPresent()) {
            String ago = humanizeAgo(Duration.between(seen.get(), Instant.now()));
            return Component.literal("Brennan was last seen online " + ago + " ago.")
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
