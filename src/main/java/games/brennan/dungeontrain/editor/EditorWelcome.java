package games.brennan.dungeontrain.editor;

import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.util.PresenceLine;
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
 * each play session. The first line lands ~2.2 seconds after entering; the next lines arrive one at a
 * time ~1.3 seconds apart, and the trailing "Brennan's presence" line waits a further ~5 seconds — so
 * the welcome reads like someone typing, with a beat before the personal sign-off.
 *
 * <p>"Entered the editor" reuses the three command paths that already fire the
 * {@code EDITOR_ACTION "entered_editor"} advancement trigger (category, carriage,
 * and tunnel enters in {@code EditorCommand}); they all route through
 * {@code EditorCommand.markEnteredEditor}, which calls {@link #showOnEnter}.</p>
 *
 * <p>Scheduling is tick-based like {@code CinematicIntroService}: each player's remaining lines are
 * parked in a queue, each carrying the delay to wait before it shows, drained one line per due tick by
 * {@link #tick} (pumped once per server tick from {@code PlayerJoinEvents.onLevelTick}). "Once per
 * play session" is gated by an in-memory UUID set cleared on logout (via {@link #forget}), so a relog
 * re-shows the welcome.</p>
 */
public final class EditorWelcome {

    private EditorWelcome() {}

    /** Delay before the first line lands: 2.2 seconds at 20 ticks/second = 44 ticks. */
    private static final long WELCOME_DELAY_TICKS = 44L;

    /** Gap between consecutive body lines: 1.3 seconds at 20 ticks/second = 26 ticks. */
    private static final long LINE_GAP_TICKS = 26L;

    /** Extra beat before the trailing presence line: 5 seconds at 20 ticks/second = 100 ticks. */
    private static final long PRESENCE_DELAY_TICKS = 100L;

    /** Players already welcomed (or currently scheduled) this session; gates "once per session". */
    private static final Set<UUID> WELCOMED = ConcurrentHashMap.newKeySet();

    /** Player UUID → the remaining welcome lines to send (each with its own pre-delay). */
    private static final Map<UUID, Deque<Line>> PENDING = new ConcurrentHashMap<>();

    /** Player UUID → overworld game-tick at which the next queued line should be sent. */
    private static final Map<UUID, Long> NEXT_SEND = new ConcurrentHashMap<>();

    /** One queued welcome line: the ticks to wait before it shows, and a supplier built fresh at send time. */
    private record Line(long delayTicks, Supplier<Component> supplier) {}

    /**
     * The first time {@code player} enters the editor this session, queue the welcome lines and
     * schedule the first to land {@link #WELCOME_DELAY_TICKS} ticks (≈2.2s) later; a no-op on every
     * later entry until they log out and back in.
     */
    public static void showOnEnter(ServerPlayer player) {
        if (!WELCOMED.add(player.getUUID())) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Deque<Line> lines = new ArrayDeque<>(welcomeLines());
        PENDING.put(player.getUUID(), lines);
        NEXT_SEND.put(player.getUUID(), server.overworld().getGameTime() + lines.peek().delayTicks());
    }

    /**
     * Per-tick pump (hook from the overworld {@code LevelTickEvent.Post}, next to
     * {@code CinematicIntroService.tick}). Sends at most one queued line per player whose deadline has
     * elapsed, then re-arms the deadline by the next line's own delay until the player's queue drains.
     */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        long now = server.overworld().getGameTime();
        PENDING.entrySet().removeIf(e -> {
            UUID id = e.getKey();
            Long due = NEXT_SEND.get(id);
            if (due == null || now < due) return false; // not this line's turn yet
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) { NEXT_SEND.remove(id); return true; } // logged off mid-welcome
            Deque<Line> lines = e.getValue();
            Line current = lines.poll(); // the line whose delay just elapsed
            if (current != null) {
                Component comp = current.supplier().get();
                if (comp != null) p.sendSystemMessage(comp); // null (e.g. unknown presence) → skip, don't send blank
            }
            Line following = lines.peek();
            if (following == null) { NEXT_SEND.remove(id); return true; } // all lines delivered
            NEXT_SEND.put(id, now + following.delayTicks());
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
     * The welcome, one {@link Line} per entry — each carrying the delay before it shows and a supplier
     * evaluated at send time. The first waits {@link #WELCOME_DELAY_TICKS}; body lines
     * {@link #LINE_GAP_TICKS}; the trailing presence line {@link #PRESENCE_DELAY_TICKS} (a longer beat).
     * The presence supplier is {@link #buildPresenceLine() omitted} (returns {@code null}) when
     * Brennan's Discord presence is unknown.
     */
    private static List<Line> welcomeLines() {
        return List.of(
            new Line(WELCOME_DELAY_TICKS, () -> Component.literal("Welcome to the Dungeon Train Editor!")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)),
            new Line(LINE_GAP_TICKS, () -> Component.literal("This is a full editor for everything in the train.")),
            new Line(LINE_GAP_TICKS, () -> Component.literal("From train carriage templates to custom loot tables editor.")),
            new Line(LINE_GAP_TICKS, () -> Component.literal("If you have any questions - message Brennan in here with ")
                .append(mentionTag())),
            new Line(LINE_GAP_TICKS, () -> Component.literal(
                "He will be very enthusiastic that you're using the editor and do what he can to support you!")),
            new Line(PRESENCE_DELAY_TICKS, EditorWelcome::buildPresenceLine));
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
            return Component.translatable("chat.dungeontrain.presence.online_now")
                .withStyle(ChatFormatting.GREEN);
        }
        Optional<Instant> seen = dp.lastSeenOnline(DungeonTrain.BRENNAN_DISCORD_ID);
        if (seen.isPresent()) {
            Component ago = PresenceLine.agoComponent(Duration.between(seen.get(), Instant.now()));
            return Component.translatable("chat.dungeontrain.presence.last_seen", ago)
                .withStyle(ChatFormatting.GRAY);
        }
        return null; // presence unknown (relay-mode default) — omit the line
    }

    /**
     * Formats a positive elapsed {@link Duration} as a coarse human phrase — "5 minutes", "1 hour",
     * "3 days". Delegates to {@link PresenceLine#humanizeAgo} so the editor welcome and the in-game
     * {@code @brennanhatton} chat reply format durations identically; kept here as the seam the existing
     * {@code EditorWelcomeTest} exercises.
     */
    static String humanizeAgo(Duration d) {
        return PresenceLine.humanizeAgo(d);
    }
}
