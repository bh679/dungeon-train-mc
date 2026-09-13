package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * Plot stamping spread across server ticks, so entering an editor category returns in one tick
 * rather than holding the server thread until every plot in the category stands.
 *
 * <p>Why: {@code /dungeontrain editor portals} used to stamp all ~86 rooms inside the command —
 * each one a synchronous chunk load plus a template placement — and on a slow machine that held
 * the server thread for nine minutes (a player log from 2026-09-12, DT 0.843.0). The client sat in
 * an empty void world the whole time, and each extra "Editor" press queued another full pass.
 * Now the command stamps the plot the player lands on, teleports them, and hands the rest here;
 * the server ticks between plots, so the player can move, open menus and stay connected.</p>
 *
 * <p>One plot is still one unit of work: a room stamp cannot be split, so a single slow room still
 * costs its tick. {@link #MAX_MILLIS_PER_TICK} caps how many <em>cheap</em> plots share a tick.</p>
 *
 * <p>Static state, one queue for the server — the editor has exactly one stamped category at a
 * time ({@link EditorStampedCategoryState}), and a new category entry replaces whatever was still
 * queued. Same lifetime rules as {@link EditorStrayBlocks}: cleared on server stop so an
 * integrated-server world swap never carries a queue across.</p>
 *
 * <p>The scheduling core ({@link #start}, {@link #runSome}, {@link #flush}, {@link #cancel}) touches
 * no level so it can be unit-tested with a fake clock; only {@link #tick} reads the world.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorStampQueue {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Wall-clock budget one tick may spend stamping before the rest waits for the next tick. */
    static final long MAX_MILLIS_PER_TICK = 20;

    /** How often the action-bar progress line is refreshed while the queue is busy. */
    private static final int PROGRESS_PERIOD_TICKS = 20;

    /** One unit of stamping work — a plot erase or a plot stamp — named for the log. */
    public record Job(String label, Runnable work) {}

    private static List<Job> queue = List.of();
    private static int cursor = 0;
    private static int failed = 0;
    private static long startedNanos = 0L;
    private static String whatFor = "";
    /** True while a job is executing — {@link #flush} inside a job is a no-op rather than a recursion. */
    private static boolean running = false;
    private static LongSupplier clock = System::nanoTime;

    private EditorStampQueue() {}

    /**
     * Replace whatever is queued with {@code jobs}. Anything the previous queue had not reached is
     * dropped — a category entry mid-fill restarts the fill rather than stacking a second pass
     * behind the first.
     */
    public static synchronized void start(List<Job> jobs, String describe) {
        queue = List.copyOf(jobs);
        cursor = 0;
        failed = 0;
        startedNanos = clock.getAsLong();
        whatFor = describe == null ? "" : describe;
        if (!queue.isEmpty()) {
            LOGGER.info("[DungeonTrain] Editor plots: {} queued for '{}'", queue.size(), whatFor);
        }
    }

    /** Drop everything still queued. */
    public static synchronized void cancel() {
        if (cursor < queue.size()) {
            LOGGER.info("[DungeonTrain] Editor plots: dropped {} queued job(s) for '{}'",
                queue.size() - cursor, whatFor);
        }
        queue = List.of();
        cursor = 0;
        failed = 0;
    }

    /** Whether any job is still waiting. */
    public static synchronized boolean isBusy() {
        return cursor < queue.size();
    }

    /** Jobs finished so far in the current queue (including failed ones). */
    public static synchronized int done() {
        return Math.min(cursor, queue.size());
    }

    /** Jobs in the current queue. */
    public static synchronized int total() {
        return queue.size();
    }

    /**
     * Run every remaining job now, on this thread. For the callers that restamp a whole kind
     * themselves — they must not race a half-finished fill. A no-op while a job is already running
     * (a job that restamps a kind would otherwise re-enter here).
     */
    public static synchronized void flush() {
        if (running) return;
        if (!isBusy()) return;
        int remaining = queue.size() - cursor;
        LOGGER.info("[DungeonTrain] Editor plots: flushing {} queued job(s) for '{}' now", remaining, whatFor);
        runSome(Long.MAX_VALUE);
    }

    /**
     * Run jobs until {@code budgetNanos} of wall-clock time is spent — always at least one, since a
     * plot cannot be split and a budget smaller than one plot must still make progress.
     *
     * @return how many jobs ran
     */
    static synchronized int runSome(long budgetNanos) {
        if (!isBusy() || running) return 0;
        long begin = clock.getAsLong();
        int ran = 0;
        running = true;
        try {
            while (cursor < queue.size()) {
                Job job = queue.get(cursor++);
                ran++;
                try {
                    job.work().run();
                } catch (Throwable t) {
                    failed++;
                    LOGGER.warn("[DungeonTrain] Editor plots: '{}' failed — skipped", job.label(), t);
                }
                if (clock.getAsLong() - begin >= budgetNanos) break;
            }
        } finally {
            running = false;
        }
        return ran;
    }

    /** Test seam: replace the clock {@link #runSome} measures its budget with. */
    static synchronized void setClock(LongSupplier nanos) {
        clock = nanos == null ? System::nanoTime : nanos;
    }

    /** Test seam: back to an empty queue and the real clock. */
    static synchronized void resetForTest() {
        cancel();
        running = false;
        clock = System::nanoTime;
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;
        tick(level);
    }

    /** One tick of stamping plus the progress line — the only part that touches the world. */
    static void tick(ServerLevel overworld) {
        if (!isBusy()) return;
        int before = done();
        runSome(MAX_MILLIS_PER_TICK * 1_000_000L);
        int now = done();
        int total = total();
        if (now >= total) {
            long ms = (clock.getAsLong() - startedNanos) / 1_000_000L;
            LOGGER.info("[DungeonTrain] Editor plots ready: {} stamped in {}.{} s ({} failed) for '{}'",
                total, ms / 1000, (ms % 1000) / 100, failed, whatFor);
            say(overworld, "Plots ready.", ChatFormatting.GREEN);
        } else if (now != before && overworld.getGameTime() % PROGRESS_PERIOD_TICKS == 0) {
            say(overworld, "Setting up plots… " + now + "/" + total, ChatFormatting.GRAY);
        }
    }

    /** Action bar to everyone up at the plots — the editor's channel for transient feedback. */
    private static void say(ServerLevel overworld, String text, ChatFormatting colour) {
        Component line = Component.literal(text).withStyle(colour);
        for (ServerPlayer player : overworld.players()) {
            if (EditorLayout.isAtPlotHeight(player.getBlockY())) {
                player.displayClientMessage(line, true);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        cancel();
    }
}
