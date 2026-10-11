package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.block.PhotographFrameBlock;
import games.brennan.dungeontrain.compat.PaintingCatalog;
import games.brennan.dungeontrain.compat.PaintingCatalog.Picture;
import games.brennan.dungeontrain.compat.PaintingTransformProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The spawn half of the per-row {@link VariantState#painting()}: a Fast Paintings
 * row with a size / picture set hangs one whole painting, centred on its cell the
 * way a vanilla painting hangs from the block it was placed against.
 *
 * <p>Queued from {@link GrowthPass#note} — every overlay placer already reports its
 * writes there — and run when the outermost scope closes, so the neighbouring cells
 * the painting spreads over are placed first. It works in <b>world space</b> off the
 * placed state's {@code facing}, so a mirrored or flipped carriage hangs its
 * paintings against the wall they actually stand beside.</p>
 *
 * <p>A picture fits when every block of it is inside the piece, free (air or
 * replaceable — never another block), and has a solid wall behind. <b>Any</b> size
 * keeps the largest pictures that fit, as vanilla does; a fixed size or picture
 * that doesn't fit leaves the cell empty rather than half a painting.</p>
 */
public final class PaintingPass {

    private static final String FACING = "facing";
    private static final String X_OFFSET = "x_offset";
    private static final String Y_OFFSET = "y_offset";
    private static final String NBT_VARIANT = "variant";

    private PaintingPass() {}

    /** One block of a hung painting: its world position and its Fast Paintings offsets from the master. */
    public record Cell(BlockPos pos, int xOffset, int yOffset) {
        public boolean master() {
            return xOffset == 0 && yOffset == 0;
        }
    }

    record Pending(GrowthPass.Target target, BlockPos anchor, BlockState placed, VariantPainting painting,
                   long roll, Predicate<BlockPos> within) {}

    /** True for a Fast Paintings painting (DT's photo frame shares the properties but is not one). */
    public static boolean isPainting(BlockState state) {
        return !(state.getBlock() instanceof PhotographFrameBlock) && PaintingTransformProcessor.isPaintingBlock(state);
    }

    /** True when {@code entry} wants a whole painting hung at a cell that was placed as {@code placed}. */
    public static boolean wants(@Nullable VariantState entry, BlockState placed) {
        return entry != null && !entry.painting().isDefault() && isPainting(placed);
    }

    static Pending pending(GrowthPass.Target target, BlockPos world, VariantState entry, BlockState placed,
                           BlockPos localPos, long seed, int index, Predicate<BlockPos> within) {
        return new Pending(target, world.immutable(), placed, entry.painting(),
            roll(seed, localPos.asLong(), index), within);
    }

    /**
     * The blocks of a {@code width × height} painting facing {@code facing}, hung from
     * {@code anchor} with vanilla's centring ({@code Painting#calculateBoundingBox}: an even
     * side shifts half a block along the run / up). The run goes along
     * {@code facing.getCounterClockWise()}; the master is the top block at the run's start
     * ({@code master = cell.above(y_offset).relative(facing.getClockWise(), x_offset)}).
     */
    public static List<Cell> layout(BlockPos anchor, Direction facing, int width, int height) {
        Direction run = facing.getCounterClockWise();
        int startAlong = -((width - 1) / 2);
        int startUp = -((height - 1) / 2);
        int topUp = startUp + height - 1;
        List<Cell> out = new ArrayList<>(width * height);
        for (int a = startAlong; a < startAlong + width; a++) {
            for (int u = startUp; u <= topUp; u++) {
                out.add(new Cell(anchor.relative(run, a).above(u), a - startAlong, topUp - u));
            }
        }
        return out;
    }

    /** Hang the painting {@code p} asks for, or clear its cell when nothing fits. */
    static void hang(Pending p) {
        LevelReader level = p.target().reader();
        // A later write in the overlay may have replaced the cell — only hang what is still there.
        if (level.getBlockState(p.anchor()).getBlock() != p.placed().getBlock()) return;
        DirectionProperty facingProp = property(p.placed(), FACING, DirectionProperty.class);
        IntegerProperty xProp = property(p.placed(), X_OFFSET, IntegerProperty.class);
        IntegerProperty yProp = property(p.placed(), Y_OFFSET, IntegerProperty.class);
        if (facingProp == null || xProp == null || yProp == null) return;
        Direction facing = p.placed().getValue(facingProp);
        if (facing.getAxis().isVertical()) return;

        List<Picture> fitting = new ArrayList<>();
        for (Picture pic : candidates(p.painting(), level)) {
            if (fits(level, p.anchor(), facing, pic, p.within())) fitting.add(pic);
        }
        Picture pic = choose(fitting, p.painting(), p.roll());
        if (pic == null) {
            p.target().set(p.anchor(), Blocks.AIR.defaultBlockState());
            return;
        }

        BlockPos master = null;
        for (Cell c : layout(p.anchor(), facing, pic.width(), pic.height())) {
            BlockState state = p.placed().setValue(xProp, c.xOffset()).setValue(yProp, c.yOffset());
            p.target().set(c.pos(), state);
            if (c.master()) master = c.pos();
        }
        if (master != null) {
            CompoundTag tag = new CompoundTag();
            tag.putString(NBT_VARIANT, pic.id().toString());
            p.target().loadBlockEntity(master, tag);
        }
    }

    /** The pictures {@code painting} allows: the fixed one, else every placeable one of the size. */
    private static List<Picture> candidates(VariantPainting painting, LevelReader level) {
        if (painting.image() != null) {
            Picture fixed = PaintingCatalog.byId(level.registryAccess(), painting.image());
            return fixed == null ? List.of() : List.of(fixed);
        }
        List<Picture> out = new ArrayList<>();
        for (Picture pic : PaintingCatalog.placeable(level.registryAccess())) {
            if (painting.sizeMatches(pic.width(), pic.height())) out.add(pic);
        }
        return out;
    }

    /**
     * The picture to hang among those that fit (in catalogue order): Any size keeps only the
     * largest, as vanilla does; then a seeded pick. {@code null} when none fit.
     */
    @Nullable
    static Picture choose(List<Picture> fitting, VariantPainting painting, long roll) {
        if (fitting.isEmpty()) return null;
        List<Picture> pool = fitting;
        if (painting.anySize() && painting.image() == null) {
            int maxArea = 0;
            for (Picture pic : fitting) maxArea = Math.max(maxArea, pic.area());
            List<Picture> largest = new ArrayList<>();
            for (Picture pic : fitting) if (pic.area() == maxArea) largest.add(pic);
            pool = largest;
        }
        return pool.get((int) Math.floorMod(roll, (long) pool.size()));
    }

    private static boolean fits(LevelReader level, BlockPos anchor, Direction facing, Picture pic,
                                Predicate<BlockPos> within) {
        if (pic.width() > VariantPainting.MAX_SIDE || pic.height() > VariantPainting.MAX_SIDE) return false;
        for (Cell c : layout(anchor, facing, pic.width(), pic.height())) {
            if (!within.test(c.pos())) return false;
            if (!c.pos().equals(anchor)) {
                BlockState there = level.getBlockState(c.pos());
                if (!there.isAir() && !there.canBeReplaced()) return false;
            }
            if (!level.getBlockState(c.pos().relative(facing.getOpposite())).isSolid()) return false;
        }
        return true;
    }

    /** Picture roll for one spawn — deterministic per seed / cell / index, salted apart from the other rolls. */
    static long roll(long seed, long cellKey, int index) {
        long h = seed * 0xD6E8FEB86659FD93L ^ cellKey * 0x9E3779B97F4A7C15L ^ (index + 0x3C6EF372L) * 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 31);
        h *= 0x94D049BB133111EBL;
        h ^= (h >>> 29);
        return h;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static <T extends Property<?>> T property(BlockState state, String name, Class<T> type) {
        Property<?> prop = state.getBlock().getStateDefinition().getProperty(name);
        return type.isInstance(prop) ? (T) prop : null;
    }
}
