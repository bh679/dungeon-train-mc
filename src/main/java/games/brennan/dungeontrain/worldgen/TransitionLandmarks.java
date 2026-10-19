package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.infdev.InfdevVersion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The world-X points where the train crosses into a new dimension or band and a <b>transition flatbed</b>
 * should mark the moment ({@code train.TransitionFlatbeds}). Pure — no Minecraft types, no config reads —
 * so it unit-tests against {@link CycleLayout#DEFAULT_ORDER} directly; {@link #forRun(WorldGenCycle, int)}
 * feeds it the live cycle.
 *
 * <p>Every point is derived from the same geometry the band code ramps on ({@link CycleLayout} slot starts,
 * {@link CycleLayout#netherCoreStart}, the End group, the legacy eras' cores), in <b>base</b> blocks, then
 * mapped to world X for doubling run {@code k} ({@code startX + runStart(k) + u·2^k}). A band missing from the
 * order (disabled in config) yields no landmark. With the shipped defaults, run 0:</p>
 * <pre>
 *   nether_rise      2,782     first Nether's mountain 1 (end of the beach)
 *   nether_core_0/1  3,282 / 26,226   full-strength Nether core
 *   end_void_0/1     9,814 / 35,488   End band's erosion fade into the void
 *   end_islands_0/1 10,554 / 36,228   End islands core
 *   upside_down     15,094    upside-down core
 *   reassembly_mid  19,094    middle of the Reassembly
 *   spheres_peak    47,468    spheres structure-boost peak (max buildings)
 *   amplified       49,268    the sunk approach to Amplified
 *   far_lands       57,708  ×3 Far Lands core start — three flatbeds in a row, the last at the line
 *   far_lands_mid   59,868    middle of the Far Lands core
 *   far_lands_exit  62,028    end of the Far Lands core
 *   pyramid        ~77,224    Infdev 227 (brick pyramids) takes over
 *   superflat       80,888    Superflat core
 *   chuncks         83,698    Chunk Error band starts
 *   stacks          91,198    Stacks band starts
 * </pre>
 */
public final class TransitionLandmarks {

    /**
     * One landmark, flatbedded on the carriage the lead player is predicted to be standing in as they cross
     * it (train speed + their own recent pace through the carriages). {@code id} is stable across sessions ({@code name@r<run>}) — the claim key the planner
     * persists; {@code count} is how many flatbeds in a row end at the landmark (3 for the Far Lands approach).
     */
    public record Landmark(String id, String name, long worldX, int count) {}

    /** Flatbeds in a row on the approach to the Far Lands. */
    public static final int FAR_LANDS_APPROACH_COUNT = 3;

    private TransitionLandmarks() {}

    /** The landmarks of doubling run {@code run} under the live cycle; empty on the classic (layout-less) order. */
    public static List<Landmark> forRun(WorldGenCycle cycle, int run) {
        if (cycle == null || !cycle.hasLayout() || run < 0) return List.of();
        int[] mults = cycle.stageMultipliers();
        return forRun(cycle.layout(), cycle.startX(), run,
            mults == null ? 1 : mults.length, cycle.stageBlocks(), cycle.beachBlocks(),
            games.brennan.dungeontrain.config.SpheresProgressionConfig.segments());
    }

    /**
     * The landmarks of run {@code run}, sorted by world X.
     *
     * @param stageCount  Nether mountain stages
     * @param stageBlocks length of each Nether mountain stage
     * @param beachBlocks the Nether's leading beach
     * @param spheres     the spheres core progression (for the structure-boost peak)
     */
    public static List<Landmark> forRun(CycleLayout layout, long startX, int run,
                                        int stageCount, int stageBlocks, int beachBlocks,
                                        SpheresSegments spheres) {
        if (layout == null || run < 0 || run > 30) return List.of();
        List<Landmark> out = new ArrayList<>();
        Adder add = (name, u, count) -> {
            if (u < 0L) return;
            long x = startX + CycleLayout.runStart(run, layout.period()) + (u << run);
            out.add(new Landmark(name + "@r" + run, name, x, count));
        };
        CycleLayout.Fades f = layout.fades();

        // Nether: the first one's mountain rise (lap 1), and every Nether's full-strength core.
        int nethers = layout.typeCount(CycleLayout.Type.NETHER);
        for (int occ = 0; occ < nethers; occ++) {
            int i = layout.indexOfOccurrence(CycleLayout.Type.NETHER, occ);
            if (i < 0) continue;
            if (occ == 0) {
                // Mountain 1 begins where the beach ends — when the rise splits cleanly into beach + stages
                // (BandStages.nether's own test); otherwise the rise is one stage and starts with the slot.
                long beach = Math.max(0, beachBlocks);
                boolean split = beach + (long) Math.max(1, stageCount) * Math.max(0, stageBlocks) == Math.max(0, f.riseLen());
                add.at("nether_rise", layout.start(i) + (split ? beach : 0L), 1);
            }
            add.at("nether_core_" + occ, layout.netherCoreStart(i), 1);
        }

        // End: one landmark pair per joined band (back-to-back End slots are one band).
        int band = 0;
        for (int i = 0; i < layout.count(); i++) {
            if (layout.slot(i).type() != CycleLayout.Type.END || layout.endGroupFirst(i) != i) continue;
            long start = layout.endGroupStart(i);
            add.at("end_void_" + band, start, 1);
            add.at("end_islands_" + band, start + 2L * Math.max(0, f.eFade()) + Math.max(0, f.eVoid()), 1);
            band++;
        }

        // Upside-down: its core, and the middle of the Reassembly that follows it.
        int ud = layout.firstIndexOf(CycleLayout.Type.UPSIDE_DOWN);
        if (ud >= 0) {
            CycleLayout.Slot s = layout.slot(ud);
            long core = layout.start(ud) + Math.max(0, f.udFade());
            add.at("upside_down", core, 1);
            long reassembly = layout.udReassembly(s);
            if (reassembly > 0L) {
                long reStart = core + Math.max(0, s.core()) + layout.udTrailingFade(s);
                add.at("reassembly_mid", reStart + reassembly / 2L, 1);
            }
        }

        // Spheres: the structure boost's peak (×20 at the window's midpoint) — "max buildings".
        int sp = layout.firstIndexOf(CycleLayout.Type.SPHERES);
        if (sp >= 0 && spheres != null) {
            long peak = (spheres.structureBoostStart() + spheres.structureBoostEnd()) / 2L;
            if (peak < Math.max(0, layout.slot(sp).core())) {
                add.at("spheres_peak", layout.start(sp) + Math.max(0, f.spheresFade()) + peak, 1);
            }
        }

        // Amplified: the sunk overworld approach when the order has one, else the era's own entry fade.
        long amplified = eraFadeStart(layout, LegacyBandKind.AMPLIFIED);
        if (amplified >= 0L) {
            long sunk = sunkApproachStart(layout, LegacyBandKind.AMPLIFIED);
            add.at("amplified", sunk >= 0L ? sunk : amplified, 1);
        }

        // Far Lands: three in a row ending at the core start, one mid-core, one at the end of the core.
        long farCore = eraCoreStart(layout, LegacyBandKind.FAR_LANDS);
        if (farCore >= 0L) {
            long len = eraCoreLen(layout, LegacyBandKind.FAR_LANDS);
            add.at("far_lands", farCore, FAR_LANDS_APPROACH_COUNT);
            add.at("far_lands_mid", farCore + len / 2L, 1);
            add.at("far_lands_exit", farCore + len, 1);
        }

        // Pyramid: where Infdev 20100227 (the brick pyramids) takes over — the same share of the era's
        // entry-fade → exit-fade span InfdevVersion.at switches on (WorldGenCycle.legacyProgress).
        int is = layout.legacySlotOf(LegacyBandKind.INFDEV);
        int ie = layout.eraIndex(LegacyBandKind.INFDEV);
        if (is >= 0 && ie >= 0) {
            long from = layout.start(is) + layout.eraCoreStart(is, ie) - layout.fadeBefore(is, ie);
            long span = layout.fadeBefore(is, ie) + layout.eraCoreLen(is, ie) + layout.fadeBefore(is, ie + 1);
            add.at("pyramid", from + (long) Math.ceil(span * InfdevVersion.V227_FROM), 1);
        }

        long superflat = eraCoreStart(layout, LegacyBandKind.SUPERFLAT);
        if (superflat >= 0L) add.at("superflat", superflat, 1);

        int ch = layout.firstIndexOf(CycleLayout.Type.CHUNCKS);
        if (ch >= 0) add.at("chuncks", layout.start(ch), 1);

        int st = layout.firstIndexOf(CycleLayout.Type.STACKS);
        if (st >= 0) add.at("stacks", layout.start(st), 1);

        out.sort(Comparator.comparingLong(Landmark::worldX));
        return List.copyOf(out);
    }

    /** Base offset of era {@code kind}'s core, or {@code -1} when the order has none. */
    static long eraCoreStart(CycleLayout layout, LegacyBandKind kind) {
        int s = layout.legacySlotOf(kind);
        int e = layout.eraIndex(kind);
        return s < 0 || e < 0 ? -1L : layout.start(s) + layout.eraCoreStart(s, e);
    }

    private static long eraCoreLen(CycleLayout layout, LegacyBandKind kind) {
        int s = layout.legacySlotOf(kind);
        int e = layout.eraIndex(kind);
        return s < 0 || e < 0 ? 0L : layout.eraCoreLen(s, e);
    }

    /** Base offset where the fade into era {@code kind} starts, or {@code -1}. */
    private static long eraFadeStart(CycleLayout layout, LegacyBandKind kind) {
        int s = layout.legacySlotOf(kind);
        int e = layout.eraIndex(kind);
        return s < 0 || e < 0 ? -1L : layout.start(s) + layout.eraCoreStart(s, e) - layout.fadeBefore(s, e);
    }

    /**
     * Start of the {@code ow:sunk} gap that leads straight into era {@code kind} (it opens its legacy run),
     * or {@code -1} when there is none.
     */
    private static long sunkApproachStart(CycleLayout layout, LegacyBandKind kind) {
        int s = layout.legacySlotOf(kind);
        if (s <= 0 || layout.eraIndex(kind) != 0) return -1L;
        CycleLayout.Slot before = layout.slot(s - 1);
        return before.type() == CycleLayout.Type.OVERWORLD && before.style() == CycleLayout.Style.SUNK
            ? layout.start(s - 1) : -1L;
    }

    @FunctionalInterface
    private interface Adder {
        void at(String name, long u, int count);
    }
}
