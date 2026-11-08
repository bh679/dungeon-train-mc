package games.brennan.dungeontrain.train.id;

import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.template.TestGate;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.BandFlavor;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Stands a carriage's whole group up again from the carriage's id, as ordinary world blocks, through
 * the train's own group-building code ({@link TrainAssembler#rebuildGroup}).
 *
 * <p>The id's recorded context goes in as a {@link TestGate} (difficulty level, phase, bands) and a
 * {@link RebuildScope} (portal verdict, transition flatbeds), so every pick the train made is drawn
 * again from the same seed against the same pool. What can't be replayed is reported in
 * {@link Result#notes()} instead of being guessed at.</p>
 */
public final class CarriageRebuilder {

    private CarriageRebuilder() {}

    /**
     * A finished rebuild.
     *
     * @param box        every cell the group can occupy, pads included
     * @param anchorPIdx the group's first carriage
     * @param slotOrigin the lowest corner of the asked-for carriage
     * @param notes      what may differ from the carriage on the train, one line each
     */
    public record Result(BoundingBox box, int anchorPIdx, int groupSize, BlockPos slotOrigin, List<String> notes) {}

    /**
     * Rebuild {@code id}'s group with its back pad's lowest corner at {@code origin}.
     *
     * @throws IllegalArgumentException when the id belongs to another seed's world, or its context
     *                                  can't be read in this version
     */
    public static Result rebuild(ServerLevel overworld, CarriageId id, BlockPos origin) {
        String mark = CarriageIds.markOf(overworld);
        if (!mark.equals(id.mark())) {
            throw new IllegalArgumentException("This id is from world " + id.mark() + " and this world is " + mark
                + ". Open a world with the same seed to rebuild it.");
        }
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        CarriageDims dims = data.dims();
        int groupSize = Math.max(1, data.getGenerationConfig().groupSize());
        int anchor = GateContext.groupAnchorPIdx(id.pIdx(), groupSize);

        List<String> notes = new ArrayList<>();
        CarriageId.Full context = id.context().orElse(null);
        if (context == null) {
            CarriageIds.Lookup lookup = CarriageIds.idFor(overworld, id.pIdx());
            context = lookup.id().context().orElseThrow();
            if (!lookup.recorded()) {
                notes.add("No record of this group was kept (built before carriage ids), so its difficulty and band were worked out from the carriage number.");
            }
        }
        if (context.phase() >= TrainPhase.values().length) {
            throw new IllegalArgumentException("This id names a phase this version doesn't have; it needs a newer version.");
        }
        int version = CarriageIds.currentVersion();
        if (context.version() != version) {
            notes.add("Built on v0." + context.version() + ", rebuilt on v0." + version + ": templates may have changed since.");
        }
        if (context.forced()) {
            notes.add("An admin forcing setting was on when it was built; the rebuild uses the normal draw.");
        }
        if (context.sharedBuild().isPresent()) {
            notes.add("It stood a shared build (#" + context.sharedBuild().getAsInt()
                + "); the rebuild stands the carriage generated here instead.");
        }

        TestGate gate = new TestGate(null, context.level(), TrainPhase.values()[context.phase()], bandsOf(context.bandMask()));
        RebuildScope scope = new RebuildScope(context.portal(), context.transitionMask());
        BoundingBox box = boxFor(origin, dims, groupSize);
        discardEntities(overworld, box);
        clearMargin(overworld, box);
        Set<BlockPos> blocks = TestGate.with(gate,
            () -> RebuildScope.with(scope, () -> TrainAssembler.rebuildGroup(overworld, origin, anchor, groupSize, dims,
                () -> settleShapes(overworld, box))));
        relightAndSync(overworld, box);

        int enclosedStart = groupSize > 1 ? CarriagePlacer.halfPadLen(dims) : 0;
        BlockPos slotOrigin = origin.offset(enclosedStart + (id.pIdx() - anchor) * dims.length(), 0, 0);
        if (blocks.isEmpty()) notes.add("Nothing was placed; see the server log.");
        return new Result(box, anchor, groupSize, slotOrigin, List.copyOf(notes));
    }

    /** The cells a group of {@code groupSize} occupies from {@code origin}, its pads included. */
    public static BoundingBox boxFor(BlockPos origin, CarriageDims dims, int groupSize) {
        int length = groupSize > 1 ? groupSize * dims.length() + 2 * CarriagePlacer.halfPadLen(dims) : dims.length();
        return BoundingBox.fromCorners(origin, origin.offset(length - 1, dims.height() - 1, dims.width() - 1));
    }

    static Set<BandFlavor> bandsOf(int mask) {
        Set<BandFlavor> bands = EnumSet.noneOf(BandFlavor.class);
        for (BandFlavor b : BandFlavor.values()) {
            if ((mask & (1 << b.ordinal())) != 0) bands.add(b);
        }
        return bands;
    }

    /** Clear the last rebuild's mobs, stands and decor out of the box, so two never pile up. */
    private static void discardEntities(ServerLevel level, BoundingBox box) {
        AABB area = AABB.of(box).inflate(1.0);
        for (Entity e : level.getEntities((Entity) null, area, e -> !(e instanceof Player))) {
            e.discard();
        }
    }

    /**
     * Air out a one-block shell around the box. On the train a group has open air on every side; in
     * the basement its panes, fences and walls would otherwise join up with the rock around it.
     */
    private static void clearMargin(ServerLevel level, BoundingBox box) {
        BoundingBox outer = box.inflatedBy(1);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.betweenClosedStream(outer).map(BlockPos::immutable)
            .filter(pos -> !box.isInside(pos))
            .forEach(pos -> level.setBlock(pos, air, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
    }

    /**
     * Let every cell take the shape its neighbours give it: panes and fences joining, walls rising,
     * stairs turning corners. The train's stamp skips these updates and its group gets them when Sable
     * lifts it; a rebuild is never lifted, so it settles them here. Twice, because a stair's corner can
     * depend on a neighbour that itself changed on the first pass. Runs before the contents go in, as the
     * lift does; cells an author locked are put back after it, as the train re-locks them.
     */
    private static void settleShapes(ServerLevel level, BoundingBox box) {
        for (int pass = 0; pass < 2; pass++) {
            BlockPos.betweenClosedStream(box).map(BlockPos::immutable).forEach(pos -> {
                BlockState state = level.getBlockState(pos);
                BlockState settled = Block.updateFromNeighbourShapes(state, level, pos);
                if (settled != state) {
                    level.setBlock(pos, settled, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            });
        }
    }

    /**
     * The train writes most of a group section-local, because Sable relights and resends it when it
     * lifts the group; nothing lifts a rebuild, so relight every cell and send the box to clients.
     */
    private static void relightAndSync(ServerLevel level, BoundingBox box) {
        BlockPos.betweenClosedStream(box).map(BlockPos::immutable).forEach(pos -> {
            BlockState state = level.getBlockState(pos);
            level.getChunkSource().getLightEngine().checkBlock(pos);
            level.sendBlockUpdated(pos, state, state, 3);
        });
    }
}
