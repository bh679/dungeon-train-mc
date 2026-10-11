package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.List;

/**
 * Runs the steps that end a stream, in order, each one isolated: a step that throws is logged and
 * the rest still run (a dead ffmpeg must not leave the relay channel claimed or the session dir on
 * disk).
 *
 * <p>The steps block — ffmpeg finishing its last segment, the final upload pass, the relay stop —
 * for seconds on a good network and far longer on a bad one, so the controller runs them on their
 * own thread ({@link #runAsync}) and never on the render thread. Only game shutdown waits for them
 * ({@link #runAll}), since a daemon thread would die before the ENDLIST playlist went out.</p>
 */
final class LiveTeardown {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String THREAD_NAME = "dt-live-stop";

    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    record Step(String name, Action action) { }

    private LiveTeardown() { }

    /** Runs every step on the calling thread; a failing step is logged and skipped past. */
    static void runAll(List<Step> steps) {
        for (Step step : steps) {
            try {
                step.action().run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("[DungeonTrain] live stop: '{}' interrupted", step.name());
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] live stop: '{}' failed", step.name(), e);
            }
        }
    }

    /** Starts the steps on a daemon thread and returns at once; {@code onDone} runs after the last one. */
    static Thread runAsync(List<Step> steps, Runnable onDone) {
        List<Step> copy = List.copyOf(steps);
        Thread t = new Thread(() -> {
            try {
                runAll(copy);
            } finally {
                onDone.run();
            }
        }, THREAD_NAME);
        t.setDaemon(true);
        t.start();
        return t;
    }
}
