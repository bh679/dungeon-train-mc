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
 *       tab), and {@link #LATER_CYCLES} closes the chain. The datapack rewriter
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
    /** An End-islands band in a Biomes O' Plenty lap. */
    public static final String BOP_END = "reached_bop_end";
    public static final String UPSIDE_DOWN = "the_upside_down";
    public static final String REASSEMBLY = "reassembly_required";
    public static final String WWOO = "reached_wwoo";
    public static final String BOP = "reached_bop";
    public static final String CHUNCKS = "reached_chuncks";
    public static final String SPHERES = "reached_spheres";
    public static final String STACKS = "reached_stacks";
    /** "Re-Over-World": plain overworld at the start of the second cycle. */
    public static final String OVERWORLD_AGAIN = "reached_overworld_again";
    /**
     * "Nether Return Again": the second cycle's Nether. The id predates the advancement's current
     * meaning; it is kept so earned progress survives. Granted as action {@code nether_return_again}.
     */
    public static final String NETHER_RETURN = "read_all_nether_starting_books";

    /**
     * The chain's closing links, in the order the second cycle grants them: back on plain overworld,
     * into its (Biomes O' Plenty) Nether, then its End. Always after every first-cycle band.
     */
    public static final List<String> LATER_CYCLES = List.of(OVERWORLD_AGAIN, NETHER_RETURN, BOP_END);

    /** The advancement the chain hangs from: the first band advancement's parent. */
    public static final String ANCHOR = "carts_100";

    /**
     * Every band advancement, in the order the shipped {@link CycleLayout#DEFAULT_ORDER} visits them.
     * Also the fallback order for bands a custom layout leaves out.
     */
    public static final List<String> ALL = List.of(
            NETHER, VOID, END_ISLANDS, UPSIDE_DOWN, REASSEMBLY,
            WWOO, BETTER_NETHER, BOP, BETTER_END, SPHERES,
            legacyId(LegacyBandKind.AMPLIFIED), legacyId(LegacyBandKind.LOST_CITY), legacyId(LegacyBandKind.BETA), legacyId(LegacyBandKind.FAR_LANDS), legacyId(LegacyBandKind.CAVES_OF_CHAOS),
            legacyId(LegacyBandKind.SKYLANDS),
            legacyId(LegacyBandKind.FLOATING), legacyId(LegacyBandKind.ALPHA), legacyId(LegacyBandKind.INFDEV),
            legacyId(LegacyBandKind.CLASSIC), legacyId(LegacyBandKind.SUPERFLAT),
            CHUNCKS, STACKS,
            // Lap 1's End turns Biomes O' Plenty from the second cycle on (vanilla>bop).
            BOP_END);

    // ---- reverse journey ---------------------------------------------------------------------

    /**
     * Prefix of the <b>reverse journey</b> advancements — earned walking −X behind spawn, where the
     * {@link WorldGenCycle} lays the layout down in reverse. Each is {@code hidden} until earned (the
     * frontier reveal skips them) and none is required by {@link CompletionistAdvancement}.
     */
    public static final String REVERSE_PREFIX = "reversed_";

    /** The advancement the reverse chain hangs from: the tab root, a branch of its own. */
    public static final String REVERSE_ANCHOR = "root";

    /**
     * The forward bands that have a reverse advancement, in the order a player walking back from spawn
     * meets them in the shipped layout — all the way back to the first Nether ({@link #NETHER} last). Only
     * the first reversed run's bands: later-cycle looks (BoP End, the second Nether) are left out.
     */
    public static final List<String> REVERSE_ALL = List.of(
            STACKS, CHUNCKS,
            legacyId(LegacyBandKind.SUPERFLAT), legacyId(LegacyBandKind.CLASSIC), legacyId(LegacyBandKind.INFDEV),
            legacyId(LegacyBandKind.ALPHA), legacyId(LegacyBandKind.FLOATING), legacyId(LegacyBandKind.SKYLANDS),
            legacyId(LegacyBandKind.CAVES_OF_CHAOS), legacyId(LegacyBandKind.FAR_LANDS), legacyId(LegacyBandKind.BETA),
            legacyId(LegacyBandKind.LOST_CITY), legacyId(LegacyBandKind.AMPLIFIED),
            SPHERES, BETTER_END, BOP, BETTER_NETHER, WWOO,
            REASSEMBLY, UPSIDE_DOWN, END_ISLANDS, VOID, NETHER);

    /** The reverse advancement for forward band id {@code forwardId}: {@code reached_x} → {@code reversed_x}. */
    public static String reverseId(String forwardId) {
        if (UPSIDE_DOWN.equals(forwardId)) return REVERSE_PREFIX + "upside_down";
        if (REASSEMBLY.equals(forwardId)) return REVERSE_PREFIX + "reassembly";
        return REVERSE_PREFIX + forwardId.substring("reached_".length());
    }

    /** True when {@code path} ({@code dungeon_train/…}) names a reverse journey advancement. */
    public static boolean isReverse(String path) {
        return path != null && path.startsWith(BandAdvancementChainRewriter.PATH_PREFIX + REVERSE_PREFIX);
    }

    private BandAdvancements() {}

    /** The advancement for a legacy era: {@code reached_<kind>}, or {@code null} for the closing void, which has none. */
    public static String legacyId(LegacyBandKind kind) {
        if (kind == LegacyBandKind.VOID) return null;
        return "reached_" + kind.name().toLowerCase(Locale.ROOT);
    }

    // ---- chain -------------------------------------------------------------------------------

    /**
     * The advancement ids in the order the layout visits their bands, each once (first occurrence
     * wins), then every id of {@link #ALL} the layout has no band for, then {@link #LATER_CYCLES}.
     * Never returns an empty list; with a {@code null} layout the result is {@link #ALL} (minus the
     * later-cycle links) + {@link #LATER_CYCLES}.
     */
    public static List<String> chain(CycleLayout layout) {
        Set<String> out = new LinkedHashSet<>();
        if (layout != null) {
            for (int i = 0; i < layout.count(); i++) {
                addSlot(out, layout, i);
            }
        }
        for (String id : ALL) {
            if (!LATER_CYCLES.contains(id)) out.add(id);
        }
        out.addAll(LATER_CYCLES);
        return List.copyOf(out);
    }

    private static void addSlot(Set<String> out, CycleLayout layout, int i) {
        CycleLayout.Slot slot = layout.slot(i);
        // Only the first-run look: a first>later slot's later look is met from the second cycle on, so
        // its advancement keeps its place near the end of ALL, after the whole first cycle.
        boolean better = slot.style() == CycleLayout.Style.BETTER;
        boolean bop = slot.style() == CycleLayout.Style.BOP;
        switch (slot.type()) {
            case OVERWORLD -> {
                if (slot.style() == CycleLayout.Style.WWOO) out.add(WWOO);
                if (bop) out.add(BOP);
            }
            case NETHER -> {
                out.add(NETHER);
                if (better) out.add(BETTER_NETHER);
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
                for (LegacySpan era : layout.eras()) {
                    String id = legacyId(era.kind());
                    if (id != null) out.add(id);
                }
            }
            case MIX -> { }                                     // no band of its own: it remixes the ones behind it
        }
    }

    /**
     * The reverse advancement ids in the order a player walking back from spawn meets their bands: the
     * layout's slots last-first, and inside a slot its parts last-first too (a copy behind spawn keeps its
     * +X orientation, so −X crosses a legacy run's eras, the upside-down exit fade and an End's islands
     * before their earlier parts). Unlike {@link #chain}, each styled Nether / End is its own band — a
     * Better one gives only its Better id — so the chain runs on to the plain first Nether rather than
     * stopping at the first Nether it meets. First occurrence wins; members of {@link #REVERSE_ALL} the
     * layout lacks are appended so they stay parented. A {@code null} layout gives {@link #REVERSE_ALL}.
     */
    public static List<String> reverseChain(CycleLayout layout) {
        Set<String> out = new LinkedHashSet<>();
        if (layout != null) {
            for (int i = layout.count() - 1; i >= 0; i--) {
                addReverseSlot(out, layout, i);
            }
        }
        out.retainAll(REVERSE_ALL);
        out.addAll(REVERSE_ALL);
        return out.stream().map(BandAdvancements::reverseId).toList();
    }

    private static void addReverseSlot(Set<String> out, CycleLayout layout, int i) {
        CycleLayout.Slot slot = layout.slot(i);
        boolean better = slot.style() == CycleLayout.Style.BETTER;
        switch (slot.type()) {
            case OVERWORLD -> {
                if (slot.style() == CycleLayout.Style.WWOO) out.add(WWOO);
                if (slot.style() == CycleLayout.Style.BOP) out.add(BOP);
            }
            case NETHER -> out.add(better ? BETTER_NETHER : NETHER);
            case END -> {
                if (better) {
                    out.add(BETTER_END);
                } else {
                    out.add(END_ISLANDS);
                    out.add(VOID);
                }
            }
            case UPSIDE_DOWN -> {
                out.add(REASSEMBLY);
                out.add(UPSIDE_DOWN);
            }
            case CHUNCKS -> out.add(CHUNCKS);
            case SPHERES -> out.add(SPHERES);
            case STACKS -> out.add(STACKS);
            case LEGACY_RUN -> {
                LegacySpan[] eras = layout.eras();
                for (int e = eras.length - 1; e >= 0; e--) {
                    String id = legacyId(eras[e].kind());
                    if (id != null) out.add(id);
                }
            }
            case MIX -> { }
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
        t.add(entry(BETTER_END, (l, x) -> isInEndIslands(l, x)
                && cycle(l).isBetterEndAt(x)));
        t.add(entry(BOP_END, (l, x) -> isInEndIslands(l, x)
                && cycle(l).isBopEndAt(x)));
        t.add(entry(SPHERES, SpheresBand::isInBand));
        for (LegacyBandKind kind : LegacyBandKind.values()) {
            if (kind == LegacyBandKind.LARGE_BIOMES) continue;   // built, not shipped — see LegacyBandKind
            if (legacyId(kind) == null) continue;                // the closing void has no advancement
            t.add(entry(legacyId(kind), (l, x) -> LegacyBands.isInBand(l, kind, x)));
        }
        t.add(entry(CHUNCKS, ChuncksBand::isInBand));
        t.add(entry(STACKS, StacksBand::isInBand));
        return List.copyOf(t);
    }

    private static final List<Trigger> REVERSE_TRIGGERS = buildReverseTriggers();

    /**
     * Every reverse trigger, in {@link #REVERSE_ALL} order. Immutable. The caller gates them to
     * {@link WorldGenCycle#isMirroredAt behind spawn} and measures {@code depth} towards +X — walking
     * back, the player enters each band from its spawn side.
     */
    public static List<Trigger> reverseTriggers() {
        return REVERSE_TRIGGERS;
    }

    private static List<Trigger> buildReverseTriggers() {
        List<Trigger> out = new ArrayList<>();
        for (String id : REVERSE_ALL) {
            out.add(new Trigger(reverseId(id), forward(id).depth(), reverseTest(id)));
        }
        return List.copyOf(out);
    }

    /**
     * The column test for {@code forwardId}'s reverse advancement. The plain Nether / End tests exclude
     * their Better copy — going back, that copy is met first, and the chain must run on to the first one.
     */
    private static ColumnTest reverseTest(String forwardId) {
        return switch (forwardId) {
            case NETHER -> (l, x) -> NetherBand.isInNetherBiome(l, x) && !cycle(l).isBetterNetherAt(x);
            case VOID -> (l, x) -> DisintegrationBand.zoneAt(l, x) == Disintegration.Zone.VOID
                    && !cycle(l).isBetterEndAt(x);
            case END_ISLANDS -> (l, x) -> isInEndIslands(l, x) && !cycle(l).isBetterEndAt(x);
            default -> forward(forwardId).test();
        };
    }

    private static Trigger forward(String id) {
        for (Trigger t : TRIGGERS) {
            if (t.id().equals(id)) return t;
        }
        throw new IllegalStateException("no forward trigger for " + id);
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
