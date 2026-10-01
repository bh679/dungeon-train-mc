package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.block.stage.StagePlaceholderBlocks;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalCarriageSelection;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Fills a group with a <b>Half pair</b> — two {@link ContentsSize#HALF} templates end to end over the
 * run — once the group's {@link CarriageLayout} draw has landed on {@link CarriageLayout#HALVES}.
 *
 * <p>Each half is its own seeded weighted pick from the Half pool, so the two can differ. The gap
 * between them is filled by {@link HalfCarriageSettings#join()}. No Half template this group may
 * draw (none weighted, all gated out) means no pair, and the group is ordinary rooms.</p>
 */
public final class HalfCarriageSelection {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long HALF_SALT = 0x48414C4643415252L; // "HALFCARR"

    /** The widest centre gap a pair may leave; wider and the halves would be two rooms apart. */
    static final int MAX_GAP = 2;

    /** One half: its template, at this world's Half box. */
    public record Half(CarriageVariant shell, StructureTemplate template) {}

    /**
     * The two halves of a run, and how they join.
     *
     * @param secondOffset X of the second half from the run's origin
     * @param shortening   blocks the group's sub-level is short by — see {@link HalfJoinMode#SHORT}
     */
    public record HalfPick(Half first, Half second, HalfJoinMode join,
                           int halfLength, int secondOffset, int shortening) {}

    private HalfCarriageSelection() {}

    /** True when two Half boxes fit a {@code groupSize}-carriage run here, a gap of at most {@link #MAX_GAP} between. */
    public static boolean fits(CarriageDims dims, int groupSize) {
        if (groupSize < 2) return false;
        Optional<CarriageDims> half = ContentsSize.HALF.shellDims(dims, groupSize);
        if (half.isEmpty()) return false;
        int gap = HalfJoinMode.gap(groupSize * dims.length(), half.get().length());
        return gap >= 0 && gap <= MAX_GAP;
    }

    /**
     * The pure draw for one half — {@code which} 0 or 1 — seeded so a re-stamped group gets the same
     * two. {@code null} when no Half template has a weight.
     */
    static String draw(long seed, long groupIndex, int which, List<String> halves, ToIntFunction<String> weight) {
        int total = 0;
        for (String id : halves) total += Math.max(0, weight.applyAsInt(id));
        if (total <= 0) return null;
        return WholeCarriageSelection.weightedSeededPick(seed ^ HALF_SALT, (int) (groupIndex * 2 + which),
            halves, weight);
    }

    /** The Half templates this group may draw: weighted, built, and allowed at the anchor's gate. */
    static List<String> eligible(GateContext anchorGate) {
        CarriageWeights weights = CarriageWeights.current();
        List<String> halves = new ArrayList<>();
        for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
            if (CarriagePlacer.sizeOf(v) != ContentsSize.HALF || PortalCarriageBuilder.isPortalVariant(v)) continue;
            if (weights.weightFor(v.id()) <= 0) continue;
            // A Half carriage made blank and never built would put a hole in the train.
            if (!CarriageTemplateStore.hasBlocks(v.id())) continue;
            if (anchorGate != null && !anchorGate.allows(weights.gateFor(v.id()))) continue;
            halves.add(v.id());
        }
        return halves;
    }

    /**
     * The Half pair for this run, or null when there is none to place — no eligible Half template, a
     * portal group, or a group Half boxes don't fit.
     */
    public static HalfPick pick(ServerLevel level, int anchorPIdx, int groupSize, CarriageDims dims,
                                long worldSeed, GateContext anchorGate) {
        if (groupSize != DungeonTrainConfig.getGroupSize() || !fits(dims, groupSize)) return null;
        if (PortalCarriageSelection.isPortalGroup(level, anchorPIdx)) return null;
        List<String> halves = eligible(anchorGate);
        if (halves.isEmpty()) {
            LOGGER.info("[DungeonTrain] half pair anchorPIdx={} → NO_HALF", anchorPIdx);
            return null;
        }
        CarriageWeights weights = CarriageWeights.current();
        long groupIndex = Math.floorDiv((long) anchorPIdx, Math.max(1, groupSize));
        CarriageDims box = ContentsSize.HALF.shellDims(dims, groupSize).orElseThrow();
        Half first = resolve(level, draw(worldSeed, groupIndex, 0, halves, weights::weightFor), box, anchorPIdx);
        Half second = resolve(level, draw(worldSeed, groupIndex, 1, halves, weights::weightFor), box, anchorPIdx);
        if (first == null || second == null) return null;
        int runLength = groupSize * dims.length();
        HalfJoinMode join = HalfCarriageSettings.join().resolve(worldSeed, groupIndex);
        LOGGER.info("[DungeonTrain] half pair anchorPIdx={} → HALF_HIT first={} second={} join={}",
            anchorPIdx, first.shell().id(), second.shell().id(), join.key());
        return new HalfPick(first, second, join, box.length(),
            join.secondHalfOffset(runLength, box.length()), join.shortening(runLength, box.length()));
    }

    private static Half resolve(ServerLevel level, String id, CarriageDims box, int anchorPIdx) {
        if (id == null) return null;
        CarriageVariant shell = CarriageVariantRegistry.find(id).orElse(null);
        if (shell == null) return null;
        StructureTemplate template = CarriageTemplateStore.get(level, shell, box).orElse(null);
        if (template == null) {
            LOGGER.warn("[DungeonTrain] half pair anchorPIdx={} → shell '{}' has no {}-long template",
                anchorPIdx, id, box.length());
            return null;
        }
        return new Half(shell, template);
    }

    /**
     * Stamp both halves at {@code runOrigin}, close the gap between them, lay each half's variant
     * blocks, and return the footprint for the shipyard. Contents follow after assembly, one Half
     * box per half, like every other carriage's.
     */
    public static Set<BlockPos> place(ServerLevel level, BlockPos runOrigin, HalfPick pick, CarriageDims dims,
                                      int groupSize, long seed, int anchorPIdx) {
        return CarriageStampGuard.call(() -> {
            BlockPos second = runOrigin.offset(pick.secondOffset(), 0, 0);
            CarriagePlacer.stampTemplateAt(level, runOrigin, pick.first().template(), /*relight*/ false);
            CarriagePlacer.stampTemplateAt(level, second, pick.second().template(), /*relight*/ false);
            fillGap(level, runOrigin, pick, dims);
            CarriagePlacer.applyVariantBlocks(level, runOrigin, pick.first().shell(), dims, seed, anchorPIdx);
            CarriagePlacer.applyVariantBlocks(level, second, pick.second().shell(), dims, seed, anchorPIdx + groupSize - 1);
            Set<BlockPos> placed = new HashSet<>();
            for (int i = 0; i < Math.max(1, groupSize); i++) {
                placed.addAll(CarriagePlacer.collectFootprint(level, runOrigin.offset(i * dims.length(), 0, 0), dims));
            }
            return placed;
        });
    }

    private static void fillGap(ServerLevel level, BlockPos runOrigin, HalfPick pick, CarriageDims dims) {
        int from = pick.halfLength();
        int to = pick.secondOffset();
        switch (pick.join()) {
            case WALL -> {
                // Repeat the first half's end slice — its end wall and doorway — across the gap.
                int lastX = pick.halfLength() - 1;
                for (int x = from; x < to; x++) {
                    for (int y = 0; y < dims.height(); y++) {
                        for (int z = 0; z < dims.width(); z++) {
                            BlockState state = level.getBlockState(runOrigin.offset(lastX, y, z));
                            SilentBlockOps.setBlockSilent(level, runOrigin.offset(x, y, z), state);
                        }
                    }
                }
            }
            case BRIDGE -> {
                BlockState slab = plankSlab();
                for (int x = from; x < to; x++) {
                    for (int z = 0; z < dims.width(); z++) {
                        SilentBlockOps.setBlockSilent(level, runOrigin.offset(x, 0, z), slab);
                    }
                }
            }
            case SHORT, RANDOM -> { /* SHORT: the halves abut; RANDOM is resolved before a pick exists. */ }
        }
    }

    /** The stage's plank slab, from the scope the caller stamps under; oak outside any stage. */
    private static BlockState plankSlab() {
        BlockState placeholder = StagePlaceholderBlocks.defaultState("stage_wood_slab");
        BlockState resolved = placeholder == null ? null : StagePlacementScope.resolve(placeholder);
        if (resolved == null || StagePlaceholderBlocks.isPlaceholder(resolved)) return Blocks.OAK_SLAB.defaultBlockState();
        return resolved;
    }
}
