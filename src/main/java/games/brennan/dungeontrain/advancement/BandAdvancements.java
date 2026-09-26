package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.ChuncksBand;
import games.brennan.dungeontrain.worldgen.CycleLayout;
import games.brennan.dungeontrain.worldgen.Disintegration;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.SpheresBand;
import games.brennan.dungeontrain.worldgen.StacksBand;
import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The <b>journey advancements</b> — one per band occurrence of the {@link CycleLayout} — as a single
 * table, so the order they chain in and the columns they fire on both come from the layout instead of
 * from hand-written JSON parents and a per-band {@code if} list.
 *
 * <p>Two views of the same table:</p>
 * <ul>
 *   <li>{@link #chain(CycleLayout)} — the advancement ids in <em>layout order</em>: the order the
 *       player meets the bands riding +X. Every id in {@link #ALL} appears exactly once (a band the
 *       layout lacks is appended so its advancement stays parented rather than orphaned into its own
 *       tab), and {@link #OVERWORLD_AGAIN} closes the chain. The datapack rewriter
 *       ({@link BandAdvancementChainRewriter}) turns this into the {@code parent} fields at load.</li>
 *   <li>{@link #triggers()} — each id with the column test that grants it, for
 *       {@link games.brennan.dungeontrain.event.ZoneProgressEvents}' depth-gated scan.</li>
 * </ul>
 *
 * <p>The chain part is pure (no Minecraft types) so it unit-tests without a bootstrap. Ids are the
 * short advancement names under {@code dungeontrain:dungeon_train/}, which double as the
 * {@code gameplay_action} action ids.</p>
 */
public final class BandAdvancements {

    // ---- ids ---------------------------------------------------------------------------------

    public static final String NETHER = "reached_nether";
    public static final String BETTER_NETHER = "reached_better_nether";
    public static final String VOID = "reached_void";
    public static final String END_ISLANDS = "reached_end_islands";
    public static final String BETTER_END = "reached_better_end";
    /** A Nether band in a Biomes O' Plenty lap. */
    public static final String BOP_NETHER = "reached_bop_nether";
    /** An End-islands band in a Biomes O' Plenty lap. */
    public static final String BOP_END = "reached_bop_end";
    public static final String UPSIDE_DOWN = "the_upside_down";
    public static final String REASSEMBLY = "reassembly_required";
    public static final String WWOO = "reached_wwoo";
    public static final String BOP = "reached_bop";
    public static final String CHUNCKS = "reached_chuncks";
    public static final String SPHERES = "reached_spheres";
    public static final String STACKS = "reached_stacks";
    public static final String LEGACY_VOID = "reached_legacy_void";
    /** "Re-Over-World" — always the last link of the chain. */
    public static final String OVERWORLD_AGAIN = "reached_overworld_again";

    /** The advancement the chain hangs from: the first band advancement's parent. */
    public static final String ANCHOR = "carts_100";

    /**
     * Every band advancement, in the order the shipped {@link CycleLayout#DEFAULT_ORDER} visits them.
     * Also the fallback order for bands a custom layout leaves out.
     */
    public static final List<String> ALL = List.of(
            NETHER, VOID, END_ISLANDS, UPSIDE_DOWN, REASSEMBLY,
            WWOO, BOP, BETTER_NETHER, BOP_NETHER, BETTER_END, BOP_END, SPHERES,
            legacyId(LegacyBandKind.AMPLIFIED), legacyId(LegacyBandKind.BETA), legacyId(LegacyBandKind.FAR_LANDS), legacyId(LegacyBandKind.CAVES_OF_CHAOS),
            legacyId(LegacyBandKind.SKYLANDS),
            legacyId(LegacyBandKind.FLOATING), legacyId(LegacyBandKind.ALPHA), legacyId(LegacyBandKind.INFDEV),
            legacyId(LegacyBandKind.CLASSIC), legacyId(LegacyBandKind.SUPERFLAT), LEGACY_VOID,
            CHUNCKS, STACKS);

    private BandAdvancements() {}

    /** The advancement for a legacy era: {@code reached_<kind>}, except the closing void's {@link #LEGACY_VOID}. */
    public static String legacyId(LegacyBandKind kind) {
        if (kind == LegacyBandKind.VOID) return LEGACY_VOID;
        return "reached_" + kind.name().toLowerCase(Locale.ROOT);
    }

    // ---- chain -------------------------------------------------------------------------------

    /**
     * The advancement ids in the order the layout visits their bands, each once (first occurrence
     * wins), then every id of {@link #ALL} the layout has no band for, then {@link #OVERWORLD_AGAIN}.
     * Never returns an empty list; with a {@code null} layout the result is {@link #ALL} + the closer.
     */
    public static List<String> chain(CycleLayout layout) {
        Set<String> out = new LinkedHashSet<>();
        if (layout != null) {
            for (int i = 0; i < layout.count(); i++) {
                addSlot(out, layout, i);
            }
        }
        out.addAll(ALL);
        out.add(OVERWORLD_AGAIN);
        return List.copyOf(out);
    }

    private static void addSlot(Set<String> out, CycleLayout layout, int i) {
        CycleLayout.Slot slot = layout.slot(i);
        // A Lap 2 theme slot can wear either modded look (chosen per world), so it chains both;
        // a Lap 1 theme slot is vanilla on the first cycle, when the chain is first walked.
        boolean themed2 = slot.style() == CycleLayout.Style.THEMED && slot.themeGroup() >= 0
                && layout.themeGroupKind(slot.themeGroup()) == games.brennan.dungeontrain.worldgen.LapThemePicker.Kind.LAP2;
        boolean better = themed2 || slot.style() == CycleLayout.Style.BETTER;
        boolean bop = themed2 || slot.style() == CycleLayout.Style.BOP;
        switch (slot.type()) {
            case OVERWORLD -> {
                if (themed2 || slot.style() == CycleLayout.Style.WWOO) out.add(WWOO);
                if (bop) out.add(BOP);
            }
            case NETHER -> {
                out.add(NETHER);
                if (better) out.add(BETTER_NETHER);
                if (bop) out.add(BOP_NETHER);
            }
            case END -> {
                out.add(VOID);
                out.add(END_ISLANDS);
                if (better) out.add(BETTER_END);
                if (bop) out.add(BOP_END);
            }
            case UPSIDE_DOWN -> {
                out.add(UPSIDE_DOWN);
                out.add(REASSEMBLY);
            }
            case CHUNCKS -> out.add(CHUNCKS);
            case SPHERES -> out.add(SPHERES);
            case STACKS -> out.add(STACKS);
            case LEGACY_RUN -> {
                for (LegacySpan era : layout.eras()) out.add(legacyId(era.kind()));
            }
        }
    }

    // ---- triggers ----------------------------------------------------------------------------

    /** A column test: does world-X {@code worldX} of {@code overworld} read as this band's core? */
    @FunctionalInterface
    public interface ColumnTest {
        boolean test(ServerLevel overworld, int worldX);
    }

    /**
     * One depth-gated trigger: {@code id} is granted once the player's column and the column
     * {@code depth} blocks behind them both pass {@code test}.
     */
    public record Trigger(String id, int depth, ColumnTest test) {}

    /**
     * How far (blocks) into a band core the player must be before its advancement is granted — so it
     * reads as "properly inside", not "just touched the leading edge". The train travels +X, so the
     * player enters from the -X side; every band core is one contiguous X range, so two in-band samples
     * prove everything between is in-band too. Shared by every band (the Nether's original value).
     */
    public static final int ENTRY_DEPTH_BLOCKS = 400;

    /**
     * How far into the upside-down exit crossfade before {@link #REASSEMBLY} — "well into the
     * dispersing islands". The default exit fade (10,000 blocks) comfortably contains it.
     */
    public static final int REASSEMBLY_DEPTH_BLOCKS = 3800;

    private static final List<Trigger> TRIGGERS = buildTriggers();

    /** Every band trigger, in {@link #ALL} order. Immutable. */
    public static List<Trigger> triggers() {
        return TRIGGERS;
    }

    private static List<Trigger> buildTriggers() {
        List<Trigger> t = new ArrayList<>();
        t.add(entry(NETHER, NetherBand::isInNetherBiome));
        t.add(entry(VOID, (l, x) -> DisintegrationBand.zoneAt(l, x) == Disintegration.Zone.VOID));
        t.add(entry(END_ISLANDS, BandAdvancements::isInEndIslands));
        t.add(entry(UPSIDE_DOWN, UpsideDownBand::isInBand));
        t.add(new Trigger(REASSEMBLY, REASSEMBLY_DEPTH_BLOCKS, UpsideDownBand::isInExitFade));
        t.add(entry(WWOO, (l, x) -> overworldStyle(l, x) == CycleLayout.Style.WWOO));
        t.add(entry(BETTER_NETHER, (l, x) -> NetherBand.isInNetherBiome(l, x)
                && cycle(l).isBetterNetherAt(x)));
        t.add(entry(BOP, (l, x) -> overworldStyle(l, x) == CycleLayout.Style.BOP));
        t.add(entry(BOP_NETHER, (l, x) -> NetherBand.isInNetherBiome(l, x)
                && cycle(l).isBopNetherAt(x)));
        t.add(entry(BETTER_END, (l, x) -> isInEndIslands(l, x)
                && cycle(l).isBetterEndAt(x)));
        t.add(entry(BOP_END, (l, x) -> isInEndIslands(l, x)
                && cycle(l).isBopEndAt(x)));
        t.add(entry(SPHERES, SpheresBand::isInBand));
        for (LegacyBandKind kind : LegacyBandKind.values()) {
            if (kind == LegacyBandKind.LARGE_BIOMES) continue;   // built, not shipped — see LegacyBandKind
            t.add(entry(legacyId(kind), (l, x) -> LegacyBands.isInBand(l, kind, x)));
        }
        t.add(entry(CHUNCKS, ChuncksBand::isInBand));
        t.add(entry(STACKS, StacksBand::isInBand));
        return List.copyOf(t);
    }

    private static Trigger entry(String id, ColumnTest test) {
        return new Trigger(id, ENTRY_DEPTH_BLOCKS, test);
    }

    private static boolean isInEndIslands(ServerLevel overworld, int worldX) {
        return DisintegrationBand.zoneAt(overworld, worldX) == Disintegration.Zone.END_ISLANDS;
    }

    /** The styled overworld gap at {@code worldX}, or {@code null} in a world without a train / off-layout. */
    private static CycleLayout.Style overworldStyle(ServerLevel overworld, int worldX) {
        if (!DungeonTrainWorldData.get(overworld).startsWithTrain()) return null;
        return cycle(overworld).overworldStyleAt(worldX);
    }

    private static WorldGenCycle cycle(ServerLevel overworld) {
        return WorldGenCycle.fromConfig();
    }
}
