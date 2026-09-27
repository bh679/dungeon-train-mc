package games.brennan.dungeontrain.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Lets the Lost City's buildings sit in the world's own ground instead of on their template's pad.
 *
 * <p>Every Big Lost City template carries a fully filled one-block <b>pad</b> at its bottom layer: natural
 * ground (grass, dirt, moss, stone, gravel, sand, a puddle) mixed with built ground (cobblestone and smooth
 * stone roads, deepslate tiles, concrete). Vanilla's {@code beard_thin} adaptation raises terrain to that
 * pad and carves a smooth Gaussian above it, then the template overwrites its whole box — so the pad stamped
 * a flat plane of foreign ground over the stretch's surface, and the template's <em>air</em> cut a vertical
 * face into any hillside inside the footprint.</p>
 *
 * <p>This processor splits the template into base and structure:</p>
 * <ul>
 *   <li><b>Base</b> — a pad block that is natural ground ({@link #BASE}), and the plant cover on top of the
 *   pad (bushes, carpets, vines, anything replaceable). Yielded to the world wherever the world already
 *   holds natural ground there, so the biome's own surface shows through; kept where the world is air, so a
 *   pad hanging over a dip has no holes.</li>
 *   <li><b>Air</b> above the pad — yielded to the world while the world column from the pad up to it is
 *   contiguous natural ground, so a hill on the uphill side leans into the outer rooms as a smooth ramp.
 *   The contiguity walk keeps no shelf over a gap the beard carved.</li>
 *   <li><b>Structure</b> — everything else (roads, pavements, walls, floors, props, and natural blocks above
 *   the pad such as planters) is placed as the template says.</li>
 * </ul>
 *
 * <p>The buildings generate with terrain adaptation <b>off</b> ({@code StructureTerrainAdaptationMixin}):
 * vanilla's {@code beard_thin} would level the footprint before any of this ran. So the stretch's own ground
 * runs through the box untouched — where a hill stands higher than the pad it climbs over the lower floors
 * and buries them (walls and floors are still placed inside it) — and where the pad hangs over lower ground
 * on the downhill side, {@link #finalizeProcessing} props it up with a footing column: dirt under natural
 * pad blocks, stone under roads and plazas, down to the ground or {@link #FOOTING_MAX_DEPTH}.</p>
 *
 * <p>Attached at runtime by {@code SinglePoolElementMixin} to every pool element that places a
 * {@code big_lost_city} template — the mod's own pools and DT's trackside copies alike. Runtime-only, never
 * serialised, so {@link #getType()} is a unit codec. Reads the world only inside the placement box:
 * {@code processBlockInfos} runs processors before the chunk-box filter, and a read outside the region
 * would throw.</p>
 */
public final class LostCityGroundProcessor extends StructureProcessor {

    public static final LostCityGroundProcessor INSTANCE = new LostCityGroundProcessor();

    private static final StructureProcessorType<LostCityGroundProcessor> TYPE = () -> MapCodec.unit(INSTANCE);

    /** Deepest footing placed under a pad hanging over lower ground. */
    static final int FOOTING_MAX_DEPTH = 24;

    /** Namespace of the templates this processor is attached to. */
    private static final String TEMPLATE_NAMESPACE = "big_lost_city";

    /** Pad-layer blocks that read as natural ground rather than as part of the building. */
    static final Set<Block> BASE = Set.of(
            Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.PODZOL,
            Blocks.MYCELIUM, Blocks.MUD, Blocks.MOSS_BLOCK, Blocks.STONE, Blocks.ANDESITE, Blocks.GRANITE,
            Blocks.DIORITE, Blocks.TUFF, Blocks.DEEPSLATE, Blocks.DRIPSTONE_BLOCK, Blocks.GRAVEL, Blocks.SAND,
            Blocks.RED_SAND, Blocks.CLAY, Blocks.DIRT_PATH, Blocks.FARMLAND, Blocks.SNOW_BLOCK, Blocks.WATER);

    /**
     * World blocks that count as natural ground — the raw terrain present at the surface-structures step.
     * An explicit set rather than block tags: tags are only bound once a world's data loads, and this is
     * also read by unit tests.
     */
    private static final Set<Block> GROUND = Set.of(
            Blocks.STONE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.DEEPSLATE, Blocks.TUFF,
            Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT,
            Blocks.ROOTED_DIRT, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.MUD, Blocks.MOSS_BLOCK, Blocks.SAND,
            Blocks.RED_SAND, Blocks.GRAVEL, Blocks.CLAY, Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.SNOW_BLOCK,
            Blocks.PACKED_ICE, Blocks.TERRACOTTA, Blocks.WHITE_TERRACOTTA, Blocks.ORANGE_TERRACOTTA,
            Blocks.MAGENTA_TERRACOTTA, Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA, Blocks.LIME_TERRACOTTA,
            Blocks.PINK_TERRACOTTA, Blocks.GRAY_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA, Blocks.CYAN_TERRACOTTA,
            Blocks.PURPLE_TERRACOTTA, Blocks.BLUE_TERRACOTTA, Blocks.BROWN_TERRACOTTA, Blocks.GREEN_TERRACOTTA,
            Blocks.RED_TERRACOTTA, Blocks.BLACK_TERRACOTTA);

    private LostCityGroundProcessor() {}

    /** Whether {@code template} is one of the Big Lost City mod's, so its pool element gets this processor. */
    public static boolean appliesTo(ResourceLocation template) {
        return template != null && TEMPLATE_NAMESPACE.equals(template.getNamespace());
    }

    /** A pad block the world may replace. */
    static boolean isBase(BlockState state) {
        return BASE.contains(state.getBlock());
    }

    /** Plant cover that stands on the pad: yields to intruding ground like air does. */
    static boolean isCover(BlockState state) {
        Block block = state.getBlock();
        return block instanceof BushBlock || block instanceof CarpetBlock || block instanceof VineBlock
                || block == Blocks.HANGING_ROOTS || state.canBeReplaced();
    }

    /** Whether a world block is natural ground: solid, dry, and terrain rather than another piece's block. */
    static boolean isNaturalGround(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty() && GROUND.contains(state.getBlock());
    }

    /**
     * Whether the template block at local height {@code localY} yields to the world, given the world column
     * from the pad level ({@code world.apply(0)}) up to that height ({@code world.apply(localY)}).
     *
     * <p>Pad base yields when the world already has ground there. Air and plant cover yield while every
     * world block from the pad up to them is ground — no shelf is kept over a carved gap.</p>
     */
    static boolean yields(BlockState template, int localY, IntFunction<BlockState> world) {
        if (localY == 0) {
            return isBase(template) && isNaturalGround(world.apply(0));
        }
        if (!template.isAir() && !isCover(template)) return false;
        for (int y = 0; y <= localY; y++) {
            if (!isNaturalGround(world.apply(y))) return false;
        }
        return true;
    }

    @Override
    @Nullable
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader world, BlockPos offset, BlockPos pivot,
                                                              StructureTemplate.StructureBlockInfo original,
                                                              StructureTemplate.StructureBlockInfo target,
                                                              StructurePlaceSettings settings) {
        if (target == null) return null;
        int localY = original.pos().getY();
        BlockState state = target.state();
        if (localY == 0 ? !isBase(state) : !state.isAir() && !isCover(state)) return target;
        BoundingBox box = settings.getBoundingBox();
        BlockPos at = target.pos();
        if (box != null && !box.isInside(at)) return target;   // never read outside the region
        int padY = at.getY() - localY;
        if (box != null && padY < box.minY()) return target;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        boolean yields = yields(state, localY, y -> world.getBlockState(cursor.set(at.getX(), padY + y, at.getZ())));
        return yields ? null : target;
    }

    /** The block a footing column is built of under this pad block: earth under ground, stone under paving. */
    static BlockState footingFor(BlockState pad) {
        return isBase(pad) ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    /**
     * How many footing blocks go under a pad block, given the world column below it ({@code below.apply(1)} is
     * one block under the pad): down to the first natural ground, at most {@link #FOOTING_MAX_DEPTH}; none
     * when ground is directly underneath or the pad cell is air.
     */
    static int footingDepth(BlockState pad, IntFunction<BlockState> below) {
        if (pad.isAir()) return 0;
        int depth = 0;
        while (depth < FOOTING_MAX_DEPTH && !isNaturalGround(below.apply(depth + 1))) depth++;
        return depth;
    }

    @Override
    public List<StructureTemplate.StructureBlockInfo> finalizeProcessing(ServerLevelAccessor level, BlockPos offset,
                                                                         BlockPos pivot,
                                                                         List<StructureTemplate.StructureBlockInfo> originals,
                                                                         List<StructureTemplate.StructureBlockInfo> processed,
                                                                         StructurePlaceSettings settings) {
        int padY = offset.getY();   // rotation and mirroring keep Y, so the template's y=0 lands here
        BoundingBox box = settings.getBoundingBox();
        List<StructureTemplate.StructureBlockInfo> footing = null;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (StructureTemplate.StructureBlockInfo info : processed) {
            BlockPos at = info.pos();
            if (at.getY() != padY || (box != null && !box.isInside(at))) continue;
            int room = box != null ? at.getY() - box.minY() : FOOTING_MAX_DEPTH;
            if (room <= 0) continue;
            int depth = footingDepth(info.state(), d -> d > room ? Blocks.STONE.defaultBlockState()
                    : level.getBlockState(cursor.set(at.getX(), at.getY() - d, at.getZ())));
            if (depth == 0) continue;
            if (footing == null) footing = new ArrayList<>();
            BlockState block = footingFor(info.state());
            for (int d = 1; d <= depth; d++) {
                footing.add(new StructureTemplate.StructureBlockInfo(new BlockPos(at.getX(), at.getY() - d, at.getZ()), block, null));
            }
        }
        if (footing == null) return processed;
        List<StructureTemplate.StructureBlockInfo> out = new ArrayList<>(processed.size() + footing.size());
        out.addAll(processed);
        out.addAll(footing);
        return out;
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
