package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.util.MachineSpecs;
import org.slf4j.Logger;

/**
 * Resolves {@link CatchUpBurstMode#AUTO} to a real pacing from the machine's specs.
 *
 * <p>Why the setting needs a machine-dependent default: {@link CatchUpBurstMode#FILL} spends roughly
 * one group's {@code TrainAssembler.spawnGroup} — about 30 ms — per tick for as long as the catch-up
 * runs, on top of a server baseline near 26 ms (see {@code PhysicsSubstepTuner}). On a desktop that
 * fits inside the 50 ms tick budget; on a thin laptop it does not, and the cure for a train running
 * away becomes a stutter that lasts longer than the problem did.</p>
 *
 * <p><strong>The thresholds below are reasoned, not measured.</strong> The per-group cost and the
 * MSPT baseline are measured; the relationship between core count and whether a sustained fill
 * actually hurts is not. They are deliberately conservative — a machine has to look comfortably
 * capable before it is given FILL — and {@link #effectiveMode()} logs what it picked and from what,
 * so the bands can be judged against real reports instead of staying invisible.</p>
 *
 * <p>Reads happen on the server tick thread, per spawn decision, so the resolution is computed once
 * and cached. {@link #invalidate()} clears it when the config reloads.</p>
 */
public final class CatchUpBurstAuto {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long GIB = 1024L * 1024L * 1024L;

    /** Cores at or above which the machine is considered able to sustain a fill run. */
    static final int CORES_FOR_FILL = 8;
    /** Cores at or above which it can afford the occasional two-group tick. */
    static final int CORES_FOR_BURST = 4;

    /** Max heap ({@code -Xmx}) at or above which a fill run is affordable. */
    static final long HEAP_FOR_FILL = 6 * GIB;
    /** Max heap at or above which a two-group tick is affordable. */
    static final long HEAP_FOR_BURST = 3 * GIB;

    /**
     * Physical RAM below which the result is capped at {@link CatchUpBurstMode#BURST_TWO} however
     * generous {@code -Xmx} is. A launcher will happily hand out a heap the machine cannot back;
     * that heap then buys swapping, not headroom.
     */
    static final long RAM_FOR_UNCAPPED = 8 * GIB;

    /** Cached resolution of AUTO. Written once, read from the tick thread. */
    private static volatile CatchUpBurstMode resolved;

    private CatchUpBurstAuto() {}

    /**
     * The mode the spawner should actually use: whatever the player stored, unless that is
     * {@link CatchUpBurstMode#AUTO}, in which case the cached machine-derived answer.
     *
     * <p>Never returns {@code AUTO}.</p>
     */
    public static CatchUpBurstMode effectiveMode() {
        CatchUpBurstMode stored = DungeonTrainCommonConfig.getCatchUpBurstMode();
        if (stored != CatchUpBurstMode.AUTO) {
            return stored;
        }
        return machineMode();
    }

    /**
     * What AUTO resolves to on this machine, whatever the stored setting happens to be.
     *
     * <p>Separate from {@link #effectiveMode()} because the Options row needs it even while the
     * stored value is something else: the cycle button renders AUTO's label as "Automatic (Fill
     * all)", naming the mode the player would get if they picked it.</p>
     */
    public static CatchUpBurstMode machineMode() {
        CatchUpBurstMode cached = resolved;
        if (cached != null) {
            return cached;
        }
        // A benign race: two threads may both resolve, and both compute the same answer from the
        // same immutable machine facts. Cheaper than locking the tick thread.
        int cores = MachineSpecs.cores();
        long heap = MachineSpecs.maxHeapBytes();
        long ram = MachineSpecs.physicalMemoryBytes();
        CatchUpBurstMode picked = resolve(cores, heap, ram);
        resolved = picked;
        LOGGER.info("[DungeonTrain] Catch-up spawning AUTO resolved to {} (cores={}, maxHeap={} MiB, physicalRam={})",
                picked, cores, heap / (1024L * 1024L),
                ram > 0 ? (ram / (1024L * 1024L)) + " MiB" : "unknown");
        return picked;
    }

    /** Drop the cached resolution, so the next read recomputes it. Called on a config reload. */
    public static void invalidate() {
        resolved = null;
    }

    /**
     * Pick a pacing for a machine with these specs. Pure — no Minecraft types, no statics read —
     * so the bands can be tested directly.
     *
     * @param cores            logical cores, or {@code <= 0} if unknown
     * @param maxHeapBytes     JVM max heap, or {@code <= 0} if unknown
     * @param physicalRamBytes total physical RAM, or {@code <= 0} if unknown
     * @return one of OFF, BURST_TWO or FILL — never AUTO
     */
    static CatchUpBurstMode resolve(int cores, long maxHeapBytes, long physicalRamBytes) {
        // Unknown specs must not downgrade anyone. FILL is what every install ran before this
        // existed, so an unreadable machine keeps exactly the behaviour it already had, and the
        // failure mode of the probe is "no change" rather than "quietly worse".
        if (cores <= 0 || maxHeapBytes <= 0) {
            return CatchUpBurstMode.FILL;
        }

        CatchUpBurstMode byCores = cores >= CORES_FOR_FILL ? CatchUpBurstMode.FILL
                : cores >= CORES_FOR_BURST ? CatchUpBurstMode.BURST_TWO
                : CatchUpBurstMode.OFF;
        CatchUpBurstMode byHeap = maxHeapBytes >= HEAP_FOR_FILL ? CatchUpBurstMode.FILL
                : maxHeapBytes >= HEAP_FOR_BURST ? CatchUpBurstMode.BURST_TWO
                : CatchUpBurstMode.OFF;

        // The weaker of the two decides: eight cores do not help a 2 GB heap, and a 16 GB heap does
        // not help two cores.
        CatchUpBurstMode picked = strength(byCores) <= strength(byHeap) ? byCores : byHeap;

        if (physicalRamBytes > 0 && physicalRamBytes < RAM_FOR_UNCAPPED
                && picked == CatchUpBurstMode.FILL) {
            picked = CatchUpBurstMode.BURST_TWO;
        }
        return picked;
    }

    /** Ordering of the three real modes by how much machine they ask for. Not the enum ordinal. */
    private static int strength(CatchUpBurstMode mode) {
        return switch (mode) {
            case OFF -> 0;
            case BURST_TWO -> 1;
            case FILL -> 2;
            case AUTO -> throw new IllegalArgumentException("AUTO has no strength; it resolves to one");
        };
    }
}
