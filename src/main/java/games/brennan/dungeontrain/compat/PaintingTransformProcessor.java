package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.block.PhotographFrameBlock;
import games.brennan.dungeontrain.compat.PaintingBlockLayout.Cell;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link StructureProcessor} that keeps Fast Paintings' block paintings on the wall when a template
 * is stamped mirrored, rotated or vertically flipped.
 *
 * <p>Vanilla moves every cell of the stamp and then asks each block to mirror/rotate its own
 * state. {@code fastpaintings:painting} does neither, so a Z-flipped contents stamp (the default
 * 50 % roll, see {@code ContentsFlip}) lands the painting beside the far wall still facing the
 * near one — no support, every cell pops as a loose painting item. This processor runs at
 * {@link #finalizeProcessing} over the whole moved list, hands the painting cells to
 * {@link PaintingBlockLayout#relayout} and writes the re-derived facing, offsets and block-entity
 * ownership back. The image itself is not mirrored — the painting simply re-attaches to the wall it
 * now stands beside, which is what an author expects of a flipped interior.</p>
 *
 * <p>Touches only {@code fastpaintings:painting} cells, found by registry id and its three state
 * properties by name, so DT compiles without the mod on its classpath and a template with no
 * paintings costs one pass over the list. Attached at runtime, but its type is registered
 * ({@link #TYPE}) so a processor list holding it survives serialisation.</p>
 */
public final class PaintingTransformProcessor extends StructureProcessor {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Fast Paintings' one block. */
    public static final ResourceLocation PAINTING_BLOCK = ResourceLocation.fromNamespaceAndPath("fastpaintings", "painting");

    private static final String FACING = "facing";
    private static final String X_OFFSET = "x_offset";
    private static final String Y_OFFSET = "y_offset";

    /**
     * Registered as {@code dungeontrain:painting_transform} ({@code ModStructureProcessors}) so a
     * processor list carrying it can be serialised. The unit codec decodes to the horizontal-only
     * form — {@code yFlipped} is per-stamp runtime state, not data.
     */
    public static final StructureProcessorType<PaintingTransformProcessor> TYPE =
        () -> MapCodec.unit(new PaintingTransformProcessor(false));

    /**
     * Fast Paintings' block, resolved from the registry on first use (blocks are frozen long before
     * any stamp runs) so the per-cell check is a reference compare. Empty when the mod is absent —
     * {@code BLOCK.get} would hand back air there and match every air cell.
     */
    private static volatile Optional<Block> paintingBlock;

    private final boolean yFlipped;

    private PaintingTransformProcessor(boolean yFlipped) {
        this.yFlipped = yFlipped;
    }

    /**
     * A processor for a stamp whose horizontal transform is whatever {@code settings} carries.
     * {@code yFlipped} says the template was pre-flipped vertically ({@code ContentsFlip.verticallyFlipped}),
     * which vanilla knows nothing about.
     */
    public static PaintingTransformProcessor of(boolean yFlipped) {
        return new PaintingTransformProcessor(yFlipped);
    }

    /** A processor for a stamp with no vertical flip — the horizontal transform is read from the settings. */
    public static PaintingTransformProcessor horizontal() {
        return new PaintingTransformProcessor(false);
    }

    @Override
    public List<StructureBlockInfo> finalizeProcessing(ServerLevelAccessor level, BlockPos offset, BlockPos pos,
                                                       List<StructureBlockInfo> original,
                                                       List<StructureBlockInfo> processed,
                                                       StructurePlaceSettings settings) {
        Mirror mirror = settings.getMirror();
        Rotation rotation = settings.getRotation();
        if (mirror == Mirror.NONE && rotation == Rotation.NONE && !yFlipped) return processed;

        List<Integer> indices = new ArrayList<>();
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < processed.size(); i++) {
            StructureBlockInfo info = processed.get(i);
            Cell cell = toCell(info);
            if (cell == null) continue;
            indices.add(i);
            cells.add(cell);
        }
        if (cells.isEmpty()) return processed;

        List<Cell> relaid;
        try {
            relaid = PaintingBlockLayout.relayout(cells, mirror, rotation, yFlipped);
        } catch (RuntimeException e) {
            // A painting that pops is never worth failing a stamp over.
            LOGGER.warn("[DungeonTrain] Painting re-layout failed, stamping as authored: {}", e.toString());
            return processed;
        }

        List<StructureBlockInfo> out = new ArrayList<>(processed);
        for (int k = 0; k < indices.size(); k++) {
            int i = indices.get(k);
            out.set(i, apply(processed.get(i), relaid.get(k)));
        }
        return List.copyOf(out);
    }

    /**
     * Whether {@code state} is a cell this processor re-lays: a Fast Paintings painting (always false
     * without the mod) or DT's block photo frame, which uses the same three properties and master
     * convention ({@link games.brennan.dungeontrain.compat.photo.PhotoFrameLayout}).
     */
    public static boolean isPaintingBlock(BlockState state) {
        if (state.getBlock() instanceof PhotographFrameBlock) return true;
        Optional<Block> block = paintingBlock;
        if (block == null) {
            block = BuiltInRegistries.BLOCK.getOptional(PAINTING_BLOCK);
            paintingBlock = block;
        }
        return block.isPresent() && state.is(block.get());
    }

    /** The cell view of a painting block info, or {@code null} for anything that is not one. */
    private static Cell toCell(StructureBlockInfo info) {
        BlockState state = info.state();
        if (!isPaintingBlock(state)) return null;
        DirectionProperty facing = property(state, FACING, DirectionProperty.class);
        IntegerProperty x = property(state, X_OFFSET, IntegerProperty.class);
        IntegerProperty y = property(state, Y_OFFSET, IntegerProperty.class);
        if (facing == null || x == null || y == null) return null;
        return new Cell(info.pos(), state.getValue(facing), state.getValue(x), state.getValue(y), info.nbt());
    }

    /**
     * {@code info} rewritten to {@code after}: the re-derived state, and the block entity only where
     * the layout put it (the authored master's tag, copied onto the new master; every other cell
     * drops its tag).
     */
    private static StructureBlockInfo apply(StructureBlockInfo info, Cell after) {
        BlockState state = info.state();
        DirectionProperty facing = property(state, FACING, DirectionProperty.class);
        IntegerProperty x = property(state, X_OFFSET, IntegerProperty.class);
        IntegerProperty y = property(state, Y_OFFSET, IntegerProperty.class);
        BlockState next = state
            .setValue(facing, after.facing())
            .setValue(x, after.xOffset())
            .setValue(y, after.yOffset());
        CompoundTag nbt = after.data() instanceof CompoundTag tag ? tag.copy() : null;
        return new StructureBlockInfo(info.pos(), next, nbt);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Property<?>> T property(BlockState state, String name, Class<T> type) {
        Property<?> p = state.getBlock().getStateDefinition().getProperty(name);
        return type.isInstance(p) ? (T) p : null;
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
