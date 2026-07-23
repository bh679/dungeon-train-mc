package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Freeze forensics: a daemon thread that samples the server's tick counter
 * once a second and, when a single tick stalls past {@link #STALL_LOG_MS},
 * logs the server thread's live stack trace (repeating with a cooldown while
 * the stall persists). Exists because in-field freezes ("game locked up,
 * couldn't trade / quit hung") leave no trace once the process dies — this
 * turns the NEXT occurrence into an attributable stack in latest.log.
 *
 * <p>Pure diagnostics: never interrupts, never mutates game state. The
 * sampler thread is a daemon and exits on server stop.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ServerStallWatchdog {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A tick stuck longer than this gets its first stack dump. */
    private static final long STALL_LOG_MS = 5_000;
    /** Repeat dumps at most this often while the same stall persists. */
    private static final long REDUMP_COOLDOWN_MS = 10_000;
    private static final long SAMPLE_INTERVAL_MS = 1_000;

    private static final AtomicReference<Thread> RUNNING = new AtomicReference<>();

    private ServerStallWatchdog() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        Thread sampler = new Thread(() -> sampleLoop(server), "DT-Stall-Watchdog");
        sampler.setDaemon(true);
        Thread previous = RUNNING.getAndSet(sampler);
        if (previous != null) previous.interrupt();
        sampler.start();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        Thread sampler = RUNNING.getAndSet(null);
        if (sampler != null) sampler.interrupt();
    }

    private static void sampleLoop(MinecraftServer server) {
        int lastTick = -1;
        long tickChangedAt = System.currentTimeMillis();
        long lastDumpAt = 0;
        try {
            while (server.isRunning() && !Thread.currentThread().isInterrupted()) {
                Thread.sleep(SAMPLE_INTERVAL_MS);
                int tick = server.getTickCount();
                long now = System.currentTimeMillis();
                if (tick != lastTick) {
                    long stalled = now - tickChangedAt;
                    if (lastDumpAt > 0) {
                        LOGGER.warn("[DT-StallWatchdog] server thread recovered after ~{} ms stall (tick {} -> {})",
                            stalled, lastTick, tick);
                        lastDumpAt = 0;
                    }
                    lastTick = tick;
                    tickChangedAt = now;
                    continue;
                }
                long stalled = now - tickChangedAt;
                if (stalled >= STALL_LOG_MS && now - lastDumpAt >= REDUMP_COOLDOWN_MS) {
                    lastDumpAt = now;
                    dumpServerThread(server, stalled);
                }
            }
        } catch (InterruptedException ignored) {
            // server stopping — exit quietly
        }
    }

    private static void dumpServerThread(MinecraftServer server, long stalledMs) {
        Thread serverThread = server.getRunningThread();
        if (serverThread == null) return;
        StackTraceElement[] stack = serverThread.getStackTrace();
        StringBuilder sb = new StringBuilder(2048);
        sb.append("[DT-StallWatchdog] server tick ").append(server.getTickCount())
            .append(" stalled for ~").append(stalledMs).append(" ms — server thread state=")
            .append(serverThread.getState()).append(", stack:");
        for (StackTraceElement frame : stack) {
            sb.append("\n    at ").append(frame);
        }
        LOGGER.warn(sb.toString());
    }
}
