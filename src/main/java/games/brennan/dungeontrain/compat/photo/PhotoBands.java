package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.advancement.BandAdvancements;
import games.brennan.dungeontrain.worldgen.BandLabel;
import games.brennan.dungeontrain.worldgen.BandStages;
import games.brennan.dungeontrain.worldgen.CycleLayout;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The bands a photo taken in a band's <b>transition</b> counts for — the fades and crossfades that
 * {@link BandAdvancements#bandAt} reads as no band at all (the End's erosion fades, the first stretch of
 * the Reassembly, a legacy run's crossfades, the spheres / chuncks / stacks run-ins…).
 *
 * <p>A transition is a stage of a band slot where no band core stands. It credits the nearest core on
 * each side, looking no further than the stage it sits in and the stages either side — so the legacy
 * run's closing void (no advancement of its own) doesn't reach back to Superflat. The Nether's beach and
 * mountains never count: its photos need the real thing. Overworld gaps and the mix zone are not
 * transitions.</p>
 */
public final class PhotoBands {

    /** Scan step in base blocks (scaled by the run's doubling) — fine against fades of 120+ blocks. */
    private static final long STEP = 16L;

    /** Upper bound on samples per side, whatever the run scale. */
    private static final int MAX_SAMPLES = 1024;

    private static final Set<String> NETHER_BANDS = Set.of(BandAdvancements.NETHER, BandAdvancements.BETTER_NETHER);

    private PhotoBands() {}

    /** Forward band ids a photo at overworld X {@code worldX} earns through a transition; empty in a core or outside a band. */
    public static Set<String> transitionBandsAt(ServerLevel overworld, int worldX) {
        if (BandAdvancements.bandAt(overworld, worldX) != null) return Set.of();
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        int slot = cycle.slotIndexAt(worldX);
        if (slot < 0 || !hasTransitions(cycle.layout().slot(slot).type())) return Set.of();
        List<BandStages.Stage> stages = BandLabel.stagesOf(cycle, slot);
        BandStages.Position at = BandStages.locate(stages, cycle.slotLocal(worldX));
        if (at == null) return Set.of();
        long[] window = stageWindow(stages, at.index());
        long a = cycle.slotWorldX(worldX, window[0]);
        long b = cycle.slotWorldX(worldX, window[1] - 1);
        if (a == Long.MIN_VALUE || b == Long.MIN_VALUE) return Set.of();
        long step = STEP * cycle.runScaleAt(worldX);
        Set<String> found = new LinkedHashSet<>();
        addNearest(found, overworld, worldX, Math.min(a, b), -step);
        addNearest(found, overworld, worldX, Math.max(a, b), step);
        return excludeNether(found);
    }

    /** Slot types whose stages hold transitions worth a photo. */
    static boolean hasTransitions(CycleLayout.Type type) {
        return switch (type) {
            case OVERWORLD, MIX, NETHER -> false;
            default -> true;
        };
    }

    /**
     * Base-block range {@code [low, high)} from the start of stage {@code index - 1} to the end of stage
     * {@code index + 1}, clamped to the slot.
     */
    static long[] stageWindow(List<BandStages.Stage> stages, int index) {
        int first = Math.max(0, index - 1);
        int last = Math.min(stages.size() - 1, index + 1);
        long low = BandStages.startOf(stages, first);
        long high = BandStages.startOf(stages, last) + stages.get(last).length();
        return new long[] {low, high};
    }

    /** {@code bands} without the Nether's ids. */
    static Set<String> excludeNether(Set<String> bands) {
        Set<String> out = new LinkedHashSet<>(bands);
        out.removeAll(NETHER_BANDS);
        return Set.copyOf(out);
    }

    /** First core met walking from {@code fromX} towards {@code limitX} (inclusive), in {@code step}s. */
    private static void addNearest(Set<String> found, ServerLevel overworld, int fromX, long limitX, long step) {
        long x = fromX;
        for (int i = 0; i < MAX_SAMPLES; i++) {
            x += step;
            if (step < 0 ? x < limitX : x > limitX) x = limitX;
            String band = BandAdvancements.bandAt(overworld, (int) x);
            if (band != null) {
                found.add(band);
                return;
            }
            if (x == limitX) return;
        }
    }
}
