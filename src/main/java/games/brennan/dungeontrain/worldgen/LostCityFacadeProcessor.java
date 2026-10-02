package games.brennan.dungeontrain.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dresses a Lost City building's outside: a cornice at every floor, pilasters up the walls, or both.
 *
 * <p>An <b>exterior face</b> is a full block of the building with an air neighbour beyond which, in that
 * direction, nothing of the building stands on the same layer — so the outside of a bite, a courtyard or
 * a room never counts. A {@code ledge} block is set on the outside of every exterior face of a <b>floor
 * layer</b> (a layer holding at least {@code ledge_min_mass} of the largest layer's mass: the slab layers).
 * A {@code pilaster} block is set on the outside of every exterior face whose position along the wall is
 * a multiple of {@code pilaster_every}. Nothing is set where the template already has a block, and
 * nothing below {@code min_layer} or on the pad, so the plaza stays clear.</p>
 *
 * <p>Runs in {@link #finalizeProcessing} over the processed list, so it dresses the building as the
 * stretch and bite before it left it. Registered as {@code dungeontrain:lost_city_facade}.</p>
 */
public final class LostCityFacadeProcessor extends StructureProcessor {

    public static final MapCodec<LostCityFacadeProcessor> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BuiltInRegistries.BLOCK.byNameCodec().optionalFieldOf("ledge").forGetter(p -> p.ledge),
            BuiltInRegistries.BLOCK.byNameCodec().optionalFieldOf("pilaster").forGetter(p -> p.pilaster),
            Codec.intRange(2, 32).optionalFieldOf("pilaster_every", 6).forGetter(p -> p.pilasterEvery),
            Codec.intRange(1, 256).optionalFieldOf("min_layer", 2).forGetter(p -> p.minLayer),
            Codec.floatRange(0.0F, 1.0F).optionalFieldOf("ledge_min_mass", 0.6F).forGetter(p -> p.ledgeMinMass)
    ).apply(i, LostCityFacadeProcessor::new));

    public static final StructureProcessorType<LostCityFacadeProcessor> TYPE = () -> CODEC;

    private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private final Optional<Block> ledge, pilaster;
    private final int pilasterEvery, minLayer;
    private final float ledgeMinMass;

    public LostCityFacadeProcessor(Optional<Block> ledge, Optional<Block> pilaster, int pilasterEvery, int minLayer, float ledgeMinMass) {
        this.ledge = ledge;
        this.pilaster = pilaster;
        this.pilasterEvery = pilasterEvery;
        this.minLayer = minLayer;
        this.ledgeMinMass = ledgeMinMass;
    }

    /** A layer's mass, keyed by packed {@code (x, z)}. */
    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /**
     * Whether, on a layer whose mass is {@code layer}, the cell at {@code (x, z)} looks out of the building
     * in direction {@code dir}: no mass between it and the layer's bounds that way.
     */
    static boolean looksOut(Set<Long> layer, int x, int z, Direction dir, int minX, int maxX, int minZ, int maxZ) {
        int cx = x + dir.getStepX(), cz = z + dir.getStepZ();
        while (cx >= minX && cx <= maxX && cz >= minZ && cz <= maxZ) {
            if (layer.contains(key(cx, cz))) return false;
            cx += dir.getStepX();
            cz += dir.getStepZ();
        }
        return true;
    }

    /**
     * The ledge and pilaster blocks to add to {@code processed} (world positions) for a piece placed at
     * {@code origin}. Pure — used by the processor and its tests.
     */
    public List<StructureTemplate.StructureBlockInfo> dress(BlockPos origin, List<StructureTemplate.StructureBlockInfo> processed) {
        if (ledge.isEmpty() && pilaster.isEmpty() || processed.isEmpty()) return List.of();
        Map<Integer, Set<Long>> mass = new HashMap<>();
        Set<Long> occupied = new HashSet<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (StructureTemplate.StructureBlockInfo info : processed) {
            BlockPos at = info.pos();
            if (!info.state().isAir()) occupied.add(at.asLong());
            minX = Math.min(minX, at.getX()); maxX = Math.max(maxX, at.getX());
            minZ = Math.min(minZ, at.getZ()); maxZ = Math.max(maxZ, at.getZ());
            int layer = at.getY() - origin.getY();
            if (layer < minLayer) continue;
            if (LostCityStretchProcessor.isMass(new BlockPos(0, layer, 0), info.state())) {
                mass.computeIfAbsent(layer, y -> new HashSet<>()).add(key(at.getX(), at.getZ()));
            }
        }
        int largest = 0;
        for (Set<Long> layer : mass.values()) largest = Math.max(largest, layer.size());
        List<StructureTemplate.StructureBlockInfo> added = new ArrayList<>();
        Set<Long> taken = new HashSet<>();
        for (StructureTemplate.StructureBlockInfo info : processed) {
            BlockPos at = info.pos();
            int layerY = at.getY() - origin.getY();
            Set<Long> layer = mass.get(layerY);
            if (layer == null || !layer.contains(key(at.getX(), at.getZ()))) continue;
            boolean floor = ledge.isPresent() && layer.size() >= ledgeMinMass * largest;
            for (Direction dir : SIDES) {
                int nx = at.getX() + dir.getStepX(), nz = at.getZ() + dir.getStepZ();
                if (layer.contains(key(nx, nz)) || !looksOut(layer, at.getX(), at.getZ(), dir, minX, maxX, minZ, maxZ)) continue;
                BlockPos outside = new BlockPos(nx, at.getY(), nz);
                if (occupied.contains(outside.asLong()) || !taken.add(outside.asLong())) continue;
                int alongWall = dir.getAxis() == Direction.Axis.X ? nz : nx;
                if (floor) {
                    added.add(new StructureTemplate.StructureBlockInfo(outside, ledge.get().defaultBlockState(), null));
                } else if (pilaster.isPresent() && Math.floorMod(alongWall, pilasterEvery) == 0) {
                    added.add(new StructureTemplate.StructureBlockInfo(outside, pilaster.get().defaultBlockState(), null));
                }
            }
        }
        return added;
    }

    @Override
    public List<StructureTemplate.StructureBlockInfo> finalizeProcessing(ServerLevelAccessor level, BlockPos offset,
                                                                         BlockPos pivot,
                                                                         List<StructureTemplate.StructureBlockInfo> originals,
                                                                         List<StructureTemplate.StructureBlockInfo> processed,
                                                                         StructurePlaceSettings settings) {
        // the added blocks never carry NBT, so the memoised list is safe to share across a piece's chunk calls
        List<StructureTemplate.StructureBlockInfo> added = LostCityPlacementMemo.get(
                new LostCityPlacementMemo.Key(this, offset.asLong(), LostCityPlacementMemo.fingerprint(processed, true)),
                () -> List.copyOf(dress(offset, processed)));
        if (added.isEmpty()) return processed;
        List<StructureTemplate.StructureBlockInfo> out = new ArrayList<>(processed.size() + added.size());
        out.addAll(processed);
        out.addAll(added);
        return out;
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
