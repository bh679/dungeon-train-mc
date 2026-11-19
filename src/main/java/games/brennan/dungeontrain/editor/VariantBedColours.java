package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-entry <b>bed colours</b> for a white bed row: a 16-bit mask over {@link DyeColor#getId()}.
 * At spawn the bed becomes one of the enabled colours, picked per bed from the variant seed.
 *
 * <ul>
 *   <li>{@link #WHITE_ONLY} — the captured white bed. The default, omitted from JSON, so older
 *       templates spawn unchanged.</li>
 *   <li>Any other mask — one enabled colour per spawn (white too, when it is still on).</li>
 * </ul>
 *
 * <p>Both halves of a bed must agree, so the roll is keyed on the <b>head</b> cell: a foot half
 * looks one block along its {@code facing} ({@link #apply(BlockState, BlockPos, int, long, int)}).
 * A cell in a lock group rolls with its group, like every other option.</p>
 *
 * <p>Sidecar: {@code "colours": ["white", "red"]}; NBT / wire: the int mask.</p>
 */
public record VariantBedColours(int mask) {

    /** Every colour bit. */
    public static final int ALL_MASK = 0xFFFF;

    /** Default: the captured white bed. */
    public static final VariantBedColours WHITE_ONLY = new VariantBedColours(bit(DyeColor.WHITE));

    /** Salt distinct from the rotation / half / active / growth / candle / pot / charge rolls. */
    private static final long BED_SEED_SALT = 0x7C1B5E3A94D2F086L;

    public VariantBedColours {
        mask &= ALL_MASK;
        // At least one colour always stays on; an empty mask means the default.
        if (mask == 0) mask = 1 << DyeColor.WHITE.getId();
    }

    /** The mask bit for {@code colour}. */
    public static int bit(DyeColor colour) {
        return 1 << colour.getId();
    }

    /** True for a white bed — the gate for the Colours pill and the spawn pass. */
    public static boolean isWhiteBed(@Nullable BlockState state) {
        return state != null && state.getBlock() == Blocks.WHITE_BED;
    }

    /** True when this is the no-op default — used to skip JSON / NBT emission. */
    public boolean isDefault() {
        return mask == WHITE_ONLY.mask;
    }

    public boolean has(DyeColor colour) {
        return (mask & bit(colour)) != 0;
    }

    /** How many colours are enabled. */
    public int count() {
        return Integer.bitCount(mask);
    }

    /**
     * Return a copy with {@code colour} switched; switching off the last enabled colour is
     * ignored (returns {@code this}), so one always stays on.
     */
    public VariantBedColours toggled(DyeColor colour) {
        int next = mask ^ bit(colour);
        return next == 0 ? this : new VariantBedColours(next);
    }

    /** The enabled colours, in {@link DyeColor} id order. */
    public List<DyeColor> colours() {
        List<DyeColor> out = new ArrayList<>(count());
        for (DyeColor c : DyeColor.values()) {
            if (has(c)) out.add(c);
        }
        return out;
    }

    /**
     * {@code state} as the bed colour picked for this bed, keyed on its head cell so both halves
     * agree. Unchanged for {@link #WHITE_ONLY} or anything but a white bed.
     */
    public BlockState apply(BlockState state, BlockPos localPos, int lockId, long worldSeed, int carriageIndex) {
        if (isDefault() || !isWhiteBed(state)) return state;
        return apply(state, RotationApplier.posOrLockKey(headPos(state, localPos), lockId), worldSeed, carriageIndex);
    }

    /** {@link #apply(BlockState, BlockPos, int, long, int)} with the roll key given outright (preview). */
    public BlockState apply(BlockState state, long headKey, long worldSeed, int carriageIndex) {
        if (isDefault() || !isWhiteBed(state)) return state;
        List<DyeColor> pool = colours();
        DyeColor picked = pool.get(roll(headKey, worldSeed, carriageIndex, pool.size()));
        if (picked == DyeColor.WHITE) return state;
        return bedFor(picked).defaultBlockState()
            .setValue(BedBlock.FACING, state.getValue(BedBlock.FACING))
            .setValue(BedBlock.PART, state.getValue(BedBlock.PART))
            .setValue(BedBlock.OCCUPIED, state.getValue(BedBlock.OCCUPIED));
    }

    /** The head cell of the bed {@code state} at {@code localPos} belongs to. */
    static BlockPos headPos(BlockState state, BlockPos localPos) {
        if (state.getValue(BedBlock.PART) == BedPart.HEAD) return localPos;
        Direction facing = state.getValue(BedBlock.FACING);
        return localPos.relative(facing);
    }

    static int roll(long headKey, long worldSeed, int carriageIndex, int size) {
        long h = worldSeed ^ (long) carriageIndex * 0x9E3779B97F4A7C15L ^ headKey ^ BED_SEED_SALT;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        return (int) Math.floorMod(h, (long) size);
    }

    /** The vanilla bed of {@code colour}. */
    public static Block bedFor(DyeColor colour) {
        return switch (colour) {
            case WHITE -> Blocks.WHITE_BED;
            case ORANGE -> Blocks.ORANGE_BED;
            case MAGENTA -> Blocks.MAGENTA_BED;
            case LIGHT_BLUE -> Blocks.LIGHT_BLUE_BED;
            case YELLOW -> Blocks.YELLOW_BED;
            case LIME -> Blocks.LIME_BED;
            case PINK -> Blocks.PINK_BED;
            case GRAY -> Blocks.GRAY_BED;
            case LIGHT_GRAY -> Blocks.LIGHT_GRAY_BED;
            case CYAN -> Blocks.CYAN_BED;
            case PURPLE -> Blocks.PURPLE_BED;
            case BLUE -> Blocks.BLUE_BED;
            case BROWN -> Blocks.BROWN_BED;
            case GREEN -> Blocks.GREEN_BED;
            case RED -> Blocks.RED_BED;
            case BLACK -> Blocks.BLACK_BED;
        };
    }

    /** Sidecar names ({@link DyeColor#getSerializedName}) of the enabled colours. */
    public List<String> names() {
        List<String> out = new ArrayList<>(count());
        for (DyeColor c : colours()) out.add(c.getSerializedName());
        return out;
    }

    /** Parse sidecar colour names; {@code null} when any is unknown or the list is empty. */
    @Nullable
    public static VariantBedColours fromNames(List<String> names) {
        if (names == null || names.isEmpty()) return null;
        int m = 0;
        for (String n : names) {
            DyeColor c = n == null ? null : DyeColor.byName(n.trim(), null);
            if (c == null) return null;
            m |= bit(c);
        }
        return new VariantBedColours(m);
    }
}
