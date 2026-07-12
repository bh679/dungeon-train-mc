package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * CLIENT-side diagnostic for the "train disappears / reappears in a new position on
 * pause→resume" bug (singleplayer). The server data is provably intact (no cull /
 * reload / re-spawn), so the glitch lives in Sable's client sub-level rendering. This
 * dumps the Sable interpolation + per-carriage pose state across a client-detected
 * resume so the exact mechanism (interpolation reset / snapshot-buffer staleness /
 * render-mesh rebuild) can be read from one pause-test, instead of guessed.
 *
 * <p>Detect-resume mirrors {@code ResumeWatchdog}'s server-side trick: the client tick
 * loop freezes while paused (the integrated server is paused and {@code Minecraft}
 * skips the level tick), so a multi-second <em>wall-clock</em> gap between consecutive
 * client ticks is the resume signal. On detection it logs every tick for a short
 * window. Pure read-only via Sable's public API ({@link SubLevelContainer#getContainer}
 * → {@link ClientSubLevelContainer#getAllSubLevels()} → per-sub-level interpolator),
 * the same path {@code NearestCarriage}/{@code TrainEngineSound} already use.
 *
 * <p>Remove (or gate off) once the mechanism is confirmed and the fix lands.</p>
 */
public final class ResumeRenderDiagnostics {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Wall-clock gap (ms) between client ticks that counts as a resume-from-pause. */
    private static final long RESUME_GAP_MS = 2_000L;
    /** Ticks to log after a detected resume (~1.5 s) to capture the settle trajectory. */
    private static final int LOG_TICKS = 30;
    /** How many carriages to dump per tick (the ones nearest the player surface first). */
    private static final int MAX_SUBS = 4;

    private static long lastTickNanos = 0L;
    private static int logTicksRemaining = 0;

    private ResumeRenderDiagnostics() {}

    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            lastTickNanos = 0L; // left the world — reset baseline so re-entry isn't a "resume"
            return;
        }

        long now = System.nanoTime();
        long prev = lastTickNanos;
        lastTickNanos = now;
        if (prev != 0L) {
            long gapMs = (now - prev) / 1_000_000L;
            if (gapMs >= RESUME_GAP_MS) {
                logTicksRemaining = LOG_TICKS;
                LOGGER.info("[DT-resume-diag] CLIENT resume after {}ms wall-clock gap — dumping sub-level render state for {} ticks", gapMs, LOG_TICKS);
            }
        }

        if (logTicksRemaining > 0) {
            logTicksRemaining--;
            try {
                dump(level);
            } catch (Throwable t) {
                LOGGER.warn("[DT-resume-diag] dump failed: {}", t.toString());
            }
        }
    }

    private static void dump(ClientLevel level) {
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        ClientSableInterpolationState interp = container.getInterpolation();
        List<ClientSubLevel> subs = container.getAllSubLevels();
        LOGGER.info("[DT-resume-diag] gameTime={} subLevels={} interp.stopped={} tickPointer={} mostRecentInterpTick={} lastInterpTick={}",
            level.getGameTime(), subs.size(), interp.isStopped(),
            fmt1(interp.getTickPointer()), fmt1(interp.mostRecentInterpolationTick), fmt1(interp.lastInterpolationTick));

        int i = 0;
        for (ClientSubLevel sub : subs) {
            if (i++ >= MAX_SUBS) break;
            List<SubLevelSnapshotInterpolator.Snapshot> buf = sub.getInterpolator().buffer;
            int bufSize;
            String bufRange;
            synchronized (buf) {
                bufSize = buf.size();
                bufRange = bufSize == 0 ? "-" : (buf.get(0).gameTick() + ".." + buf.get(bufSize - 1).gameTick());
            }
            BoundingBox3dc box = sub.boundingBox();
            boolean zeroBox = box == null || (box.minX() == 0 && box.minY() == 0 && box.minZ() == 0
                && box.maxX() == 0 && box.maxY() == 0 && box.maxZ() == 0);
            Pose3dc logical = sub.logicalPose();
            Pose3dc last = sub.lastPose();
            LOGGER.info("[DT-resume-diag]   sub={} buf={}[{}] zeroBox={} logicalX={} lastX={} dPosX={}",
                shortId(sub.getUniqueId()), bufSize, bufRange, zeroBox,
                fmt2(logical.position().x()), fmt2(last.position().x()),
                fmt2(logical.position().x() - last.position().x()));
        }
    }

    private static String shortId(UUID id) {
        return id == null ? "null" : id.toString().substring(0, 8);
    }

    private static String fmt1(double d) {
        return String.format("%.1f", d);
    }

    private static String fmt2(double d) {
        return String.format("%.2f", d);
    }
}
