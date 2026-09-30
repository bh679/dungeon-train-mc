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
 * Decides whether a carriage group is a <b>Half pair</b> — one {@link ContentsSize#HALF} shell stamped
 * twice, end to end, over the run — and places it.
 *
 * <p><b>Drawn by weight.</b> Once per group, a salted seeded draw weighs every Half shell against the
 * total weight of the shells an ordinary slot would draw ({@link CarriagePlacer#enclosedSlotPool}):
 * a Half shell of weight 10 against Room shells totalling 90 takes about one group in ten. The Room
 * side winning means "not a Half pair" and the slots roll as they always have — so a world with no
 * weighted Half shell never sees one.</p>
 *
 * <p>Portal, whole-group and Full groups win a collision; the caller asks this only when none of
 * them took the group. The gap between the halves is filled by {@link HalfCarriageSettings#join()}.</p>
 */
public final class HalfCarriageSelection {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long HALF_SALT = 0x48414C4643415252L; // "HALFCARR"

    /** The draw's stand-in for "an ordinary group" — never a variant id (those are {@code [a-z0-9_]{1,32}}). */
    static final String ROOM = "";

    /** The widest centre gap a pair may leave; wider and the halves would be two rooms apart. */
    static final int MAX_GAP = 2;

    /**
     * A shell chosen for a run, its template at this world's Half box, and how the halves join.
     *
     * @param secondOffset X of the second half from the run's origin
     * @param shortening   blocks the group's sub-level is short by — see {@link HalfJoinMode#SHORT}
     */
    public record HalfPick(CarriageVariant shell, StructureTemplate template, HalfJoinMode join,
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
     * The pure draw — {@code null} for an ordinary group, else the Half shell id. {@code roomWeight}
     * is the summed weight of the ordinary slot pool; {@code halves} the eligible Half shell ids.
     */
    static String draw(long seed, long groupIndex, int roomWeight, List<String> halves, ToIntFunction<String> weight) {
        int halfTotal = 0;
        for (String id : halves) halfTotal += Math.max(0, weight.applyAsInt(id));
        if (halfTotal <= 0) return null;
        List<String> ids = new ArrayList<>(halves.size() + 1);
        ids.add(ROOM);
        ids.addAll(halves);
        String chosen = WholeCarriageSelection.weightedSeededPick(seed ^ HALF_SALT, (int) groupIndex, ids,
            id -> id.equals(ROOM) ? Math.max(0, roomWeight) : weight.applyAsInt(id));
        return chosen.equals(ROOM) ? null : chosen;
    }

    /**
     * The Half pair for this run, or null when the group is an ordinary one — no weighted Half shell,
     * the Room side won the draw, a portal group, or a group Half boxes don't fit.
     */
    public static HalfPick pick(ServerLevel level, int anchorPIdx, int groupSize, CarriageDims dims,
                                long worldSeed, GateContext anchorGate) {
        if (groupSize != DungeonTrainConfig.getGroupSize() || !fits(dims, groupSize)) return null;
        if (PortalCarriageSelection.isPortalGroup(level, anchorPIdx)) return null;
        CarriageWeights weights = CarriageWeights.current();
        List<String> halves = new ArrayList<>();
        for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
            if (CarriagePlacer.sizeOf(v) != ContentsSize.HALF || PortalCarriageBuilder.isPortalVariant(v)) continue;
            if (weights.weightFor(v.id()) <= 0) continue;
            // A Half carriage made blank and never built would put two holes in the train.
            if (!CarriageTemplateStore.hasBlocks(v.id())) continue;
            if (anchorGate != null && !anchorGate.allows(weights.gateFor(v.id()))) continue;
            halves.add(v.id());
        }
        if (halves.isEmpty()) return null;
        int roomWeight = 0;
        for (CarriageVariant v : CarriagePlacer.enclosedSlotPool()) {
            if (anchorGate != null && !anchorGate.allows(weights.gateFor(v.id()))) continue;
            roomWeight += Math.max(0, weights.weightFor(v.id()));
        }
        long groupIndex = Math.floorDiv((long) anchorPIdx, Math.max(1, groupSize));
        String chosen = draw(worldSeed, groupIndex, roomWeight, halves, weights::weightFor);
        if (chosen == null) return null;
        CarriageVariant shell = CarriageVariantRegistry.find(chosen).orElse(null);
        if (shell == null) return null;
        CarriageDims box = ContentsSize.HALF.shellDims(dims, groupSize).orElseThrow();
        StructureTemplate template = CarriageTemplateStore.get(level, shell, box).orElse(null);
        if (template == null) {
            LOGGER.warn("[DungeonTrain] half pair anchorPIdx={} → shell '{}' has no {}-long template",
                anchorPIdx, chosen, box.length());
            return null;
        }
        int runLength = groupSize * dims.length();
        HalfJoinMode join = HalfCarriageSettings.join().resolve(worldSeed, groupIndex);
        LOGGER.info("[DungeonTrain] half pair anchorPIdx={} → HALF_HIT shell={} join={}", anchorPIdx, chosen, join.key());
        return new HalfPick(shell, template, join, box.length(),
            join.secondHalfOffset(runLength, box.length()), join.shortening(runLength, box.length()));
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
            CarriagePlacer.stampTemplateAt(level, runOrigin, pick.template(), /*relight*/ false);
            CarriagePlacer.stampTemplateAt(level, second, pick.template(), /*relight*/ false);
            fillGap(level, runOrigin, pick, dims);
            CarriagePlacer.applyVariantBlocks(level, runOrigin, pick.shell(), dims, seed, anchorPIdx);
            CarriagePlacer.applyVariantBlocks(level, second, pick.shell(), dims, seed, anchorPIdx + groupSize - 1);
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
