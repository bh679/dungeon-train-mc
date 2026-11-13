package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.farlands.FarLandsShift;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The X the debug screen prints along the legacy run that holds the Far Lands, so the readout matches the
 * place rather than the train's real distance from spawn.
 *
 * <ol>
 *   <li><b>Approach</b> — from the core of the era before the Far Lands (Beta) to the Far Lands entry wall, the
 *       X climbs on an exponential ease, imperceptibly at first, and reads exactly {@link FarLandsShift#EDGE}
 *       at the wall: the same wall, and the same block, {@link FarLandsShift} samples the overflow from.</li>
 *   <li><b>Far Lands and the islands after it</b> ({@link #ISLANDS}) — the same offset, so it keeps counting
 *       up from the edge with the train.</li>
 *   <li><b>Each old era after the islands</b> ({@link #CENTRED}) — its own small world: X 0 at its core's
 *       centre.</li>
 *   <li><b>Void</b> — the negative Far Lands: {@code −EDGE} where it begins, counting up.</li>
 *   <li><b>The overworld after the run</b> — the truth.</li>
 * </ol>
 *
 * <p>Each era takes over halfway through the fade into it, the switch point the clocks and compasses use
 * ({@link WorldGenCycle#isAtOrPastFarLands}). Pure: no Minecraft types, so it unit-tests directly.</p>
 */
public final class FarLandsDebugX {

    /** The eras that follow the Far Lands still reading as the Far Lands. */
    static final Set<LegacyBandKind> ISLANDS =
            EnumSet.of(LegacyBandKind.CAVES_OF_CHAOS, LegacyBandKind.SKYLANDS, LegacyBandKind.FLOATING);
    /** Eras after the islands that read as a world of their own, X 0 at the centre. */
    static final Set<LegacyBandKind> CENTRED =
            EnumSet.of(LegacyBandKind.ALPHA, LegacyBandKind.INFDEV, LegacyBandKind.CLASSIC, LegacyBandKind.SUPERFLAT);

    /**
     * Steepness of the approach ease {@code (e^(k·t) − 1) / (e^k − 1)}: about ×3.3 per tenth of the way, so
     * the first thousand blocks move the readout by a few hundred and the last tenth by millions.
     */
    static final double APPROACH_STEEPNESS = 12.0D;

    private FarLandsDebugX() {}

    /** Blocks to add to the printed X at {@code worldX}; 0 to tell the truth. */
    public static long offsetAt(WorldGenCycle cycle, int worldX) {
        if (cycle == null) return 0L;
        WorldGenCycle.LegacyRun run = cycle.legacyRunAt(worldX);
        return run == null ? 0L : offsetIn(run, worldX);
    }

    /** {@link #offsetAt} on a run already laid out. */
    static long offsetIn(WorldGenCycle.LegacyRun run, long worldX) {
        List<WorldGenCycle.LegacyRunEra> eras = run.eras();
        int far = indexOf(eras, LegacyBandKind.FAR_LANDS);
        if (far < 0) return 0L;

        WorldGenCycle.LegacyRunEra farEra = eras.get(far);
        long wall = entryWallX(farEra);
        long reach = FarLandsShift.EDGE - wall;
        long approachFrom = far > 0 ? eras.get(far - 1).coreStartX() : farEra.fadeStartX();

        int after = far + 1;
        while (after < eras.size() && ISLANDS.contains(eras.get(after).kind())) after++;
        long islandsEnd = switchX(eras, after, run.endX());

        if (worldX < approachFrom) return 0L;
        if (worldX < wall) return reach > 0L ? approach(reach, worldX - approachFrom, wall - approachFrom) : 0L;
        if (worldX < islandsEnd) return Math.max(0L, reach);

        for (int e = after; e < eras.size(); e++) {
            long from = switchX(eras, e, run.endX());
            long to = switchX(eras, e + 1, run.endX());
            if (worldX < from || worldX >= to) continue;
            WorldGenCycle.LegacyRunEra era = eras.get(e);
            if (CENTRED.contains(era.kind())) return -Math.floorDiv(era.coreStartX() + era.coreEndX(), 2L);
            if (era.kind() == LegacyBandKind.VOID) return -(long) FarLandsShift.EDGE - from;
            return 0L;
        }
        return 0L;
    }

    /** The Far Lands entry wall in world X, laid out exactly as {@link FarLandsShift#forChunk} lays it. */
    static long entryWallX(WorldGenCycle.LegacyRunEra farLands) {
        long holdLen = farLands.coreEndX() - farLands.coreStartX();
        return farLands.coreStartX() + (holdLen <= 0L
                ? FarLandsShift.APPROACH
                : Math.round((double) FarLandsShift.APPROACH * holdLen / FarLandsShift.SCRIPT_LEN));
    }

    /**
     * Where era {@code e} takes over: halfway through the fade into it. One past the last era is halfway
     * through the run's exit fade, where the overworld takes over.
     */
    private static long switchX(List<WorldGenCycle.LegacyRunEra> eras, int e, long runEndX) {
        if (e >= eras.size()) {
            long lastCoreEnd = eras.get(eras.size() - 1).coreEndX();
            return lastCoreEnd + (runEndX - lastCoreEnd) / 2L;
        }
        WorldGenCycle.LegacyRunEra era = eras.get(e);
        return era.fadeStartX() + (era.coreStartX() - era.fadeStartX()) / 2L;
    }

    /** {@code reach} eased in over {@code len} blocks, {@code into} of the way along; never decreasing. */
    private static long approach(long reach, long into, long len) {
        if (len <= 0L) return reach;
        double t = Math.max(0.0D, Math.min(1.0D, (double) into / len));
        double eased = Math.expm1(APPROACH_STEEPNESS * t) / Math.expm1(APPROACH_STEEPNESS);
        return Math.round(reach * eased);
    }

    private static int indexOf(List<WorldGenCycle.LegacyRunEra> eras, LegacyBandKind kind) {
        for (int i = 0; i < eras.size(); i++) {
            if (eras.get(i).kind() == kind) return i;
        }
        return -1;
    }
}
