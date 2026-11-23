package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.block.state.properties.WoodType;

import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The sign side of a block-variant row: which of the four sign blocks a row is ({@link Mount}),
 * switching a row between them for the same wood, the row's text (kept in its block-entity NBT in
 * vanilla's own {@code front_text} / {@code back_text} / {@code is_waxed} shape, so every placer
 * stamps it unchanged), and the {@link VariantSign#auto} pick at spawn.
 *
 * <p>Every function returns a new value; nothing passed in is changed.</p>
 */
public final class SignVariants {

    /** The four ways a sign can hang. Ordinal is the wire value ({@code SET_SIGN_MOUNT}); 4 = Auto. */
    public enum Mount { WALL, STANDING, HANGING, WALL_HANGING }

    /** Wire value of Auto in {@code SET_SIGN_MOUNT}. */
    public static final int AUTO_WIRE = Mount.values().length;

    /** Lines per side — vanilla's {@link SignText#LINES}. */
    public static final int LINES = SignText.LINES;

    /** Vanilla's line widths in font pixels: a sign's, and a hanging sign's (narrower). */
    public static final int SIGN_LINE_WIDTH = 90;
    public static final int HANGING_LINE_WIDTH = 60;

    /** Hard cap on characters in one line, whatever the font says — keeps the wire token bounded. */
    public static final int MAX_LINE_CHARS = 48;

    public static final String TAG_FRONT = "front_text";
    public static final String TAG_BACK = "back_text";
    public static final String TAG_WAXED = "is_waxed";

    private static volatile Map<WoodType, Map<Mount, Block>> siblingCache;

    private SignVariants() {}

    // ---- what a state is ----

    /** True when {@code state} is any of the four sign blocks. */
    public static boolean isSign(@Nullable BlockState state) {
        return state != null && state.getBlock() instanceof SignBlock;
    }

    /** The mount {@code state} is, or {@code null} when it isn't a sign. */
    @Nullable
    public static Mount mountOf(@Nullable BlockState state) {
        if (state == null) return null;
        Block b = state.getBlock();
        if (b instanceof WallHangingSignBlock) return Mount.WALL_HANGING;
        if (b instanceof CeilingHangingSignBlock) return Mount.HANGING;
        if (b instanceof WallSignBlock) return Mount.WALL;
        if (b instanceof StandingSignBlock) return Mount.STANDING;
        return null;
    }

    /** True when {@code mount} hangs (the narrower 60 px lines). */
    public static boolean isHanging(Mount mount) {
        return mount == Mount.HANGING || mount == Mount.WALL_HANGING;
    }

    /** The sign block of {@code state}'s wood in {@code mount}, or {@code null} when that wood has none. */
    @Nullable
    public static Block sibling(BlockState state, Mount mount) {
        if (!(state.getBlock() instanceof SignBlock sign)) return null;
        Map<Mount, Block> byMount = siblings().get(sign.type());
        return byMount == null ? null : byMount.get(mount);
    }

    private static Map<WoodType, Map<Mount, Block>> siblings() {
        Map<WoodType, Map<Mount, Block>> cached = siblingCache;
        if (cached != null) return cached;
        Map<WoodType, Map<Mount, Block>> built = new HashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!(block instanceof SignBlock sign)) continue;
            Mount m = mountOf(block.defaultBlockState());
            if (m == null) continue;
            built.computeIfAbsent(sign.type(), t -> new EnumMap<>(Mount.class)).putIfAbsent(m, block);
        }
        siblingCache = built;
        return built;
    }

    // ---- facing ----

    /** The horizontal direction the sign's front faces. */
    public static Direction facing(BlockState state) {
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        if (state.hasProperty(BlockStateProperties.ROTATION_16)) {
            int seg = state.getValue(BlockStateProperties.ROTATION_16);
            return Direction.fromYRot(RotationSegment.convertToDegrees(seg));
        }
        return Direction.SOUTH;
    }

    /**
     * {@code state} as the same wood's {@code mount}, facing the same way (a standing sign turned
     * to a diagonal snaps to the nearest side). Waterlogging carries; a ceiling sign keeps its
     * chain style. {@code state} back unchanged when it isn't a sign or the wood has no such block.
     */
    public static BlockState withMount(BlockState state, Mount mount) {
        if (mountOf(state) == mount) return state;
        Block target = sibling(state, mount);
        if (target == null) return state;
        BlockState out = target.defaultBlockState();
        if (out.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            out = out.setValue(BlockStateProperties.HORIZONTAL_FACING, facing(state));
        } else if (out.hasProperty(BlockStateProperties.ROTATION_16)) {
            int seg = state.hasProperty(BlockStateProperties.ROTATION_16)
                ? state.getValue(BlockStateProperties.ROTATION_16)
                : RotationSegment.convertToSegment(facing(state));
            out = out.setValue(BlockStateProperties.ROTATION_16, seg);
        }
        if (out.hasProperty(BlockStateProperties.WATERLOGGED) && state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            out = out.setValue(BlockStateProperties.WATERLOGGED, state.getValue(BlockStateProperties.WATERLOGGED));
        }
        return out;
    }

    /**
     * {@code row} switched to {@code mount}, its rotation carried across: a locked row locks to
     * the new block's facing, Random stays Random, and an options set keeps the sides both kinds
     * share (none left → Random).
     */
    public static VariantState withMount(VariantState row, Mount mount) {
        BlockState next = withMount(row.state(), mount);
        if (next == row.state()) return row;
        VariantRotation rot = row.rotation();
        VariantRotation nextRot = switch (rot.mode()) {
            case LOCK -> RotationApplier.lockToCurrent(next);
            case RANDOM -> rot;
            case OPTIONS -> remapOptions(rot.dirMask(), RotationApplier.isCompass(row.state()),
                RotationApplier.isCompass(next));
        };
        return row.withState(next, row.blockEntityNbt()).withRotation(nextRot);
    }

    /** Compass slot (S0 W2 N4 E6) ↔ facing slot (Direction ordinal) for the four sides. */
    private static final Direction[] SIDES = {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST};

    static VariantRotation remapOptions(int mask, boolean fromCompass, boolean toCompass) {
        if (fromCompass == toCompass) return new VariantRotation(VariantRotation.Mode.OPTIONS, mask);
        int out = 0;
        for (int i = 0; i < SIDES.length; i++) {
            int compassSlot = i * 2;
            int facingSlot = SIDES[i].ordinal();
            int from = fromCompass ? compassSlot : facingSlot;
            int to = toCompass ? compassSlot : facingSlot;
            if ((mask & (1 << from)) != 0) out |= 1 << to;
        }
        return out == 0 ? VariantRotation.NONE : new VariantRotation(VariantRotation.Mode.OPTIONS, out);
    }

    // ---- auto ----

    /**
     * The mount an Auto sign takes at {@code pos}, given the blocks around it and the way it
     * faces, trying in order: a solid block behind (wall sign), a sturdy ceiling (hanging), a
     * sturdy block to either side (wall hanging), otherwise standing. A mount the wood lacks is
     * skipped.
     */
    public static Mount autoMount(BlockGetter level, BlockPos pos, BlockState sign) {
        Direction face = facing(sign);
        BlockPos behind = pos.relative(face.getOpposite());
        if (has(sign, Mount.WALL) && level.getBlockState(behind).isSolid()) return Mount.WALL;
        BlockPos above = pos.above();
        if (has(sign, Mount.HANGING)
            && level.getBlockState(above).isFaceSturdy(level, above, Direction.DOWN, SupportType.CENTER)) {
            return Mount.HANGING;
        }
        if (has(sign, Mount.WALL_HANGING)) {
            for (Direction side : List.of(face.getClockWise(), face.getCounterClockWise())) {
                BlockPos at = pos.relative(side);
                if (level.getBlockState(at).isFaceSturdy(level, at, side.getOpposite(), SupportType.FULL)) {
                    return Mount.WALL_HANGING;
                }
            }
        }
        if (has(sign, Mount.STANDING)) return Mount.STANDING;
        Mount current = mountOf(sign);
        return current == null ? Mount.STANDING : current;
    }

    private static boolean has(BlockState sign, Mount mount) {
        return mountOf(sign) == mount || sibling(sign, mount) != null;
    }

    // ---- text ----

    /** One side of a sign: four lines, a dye colour and glow. */
    public record Side(List<String> lines, DyeColor color, boolean glow) {
        public static final Side BLANK = new Side(List.of("", "", "", ""), DyeColor.BLACK, false);

        public Side {
            String[] clean = new String[LINES];
            for (int i = 0; i < LINES; i++) {
                String line = lines != null && i < lines.size() && lines.get(i) != null ? lines.get(i) : "";
                clean[i] = clampLine(line);
            }
            lines = List.of(clean);
            if (color == null) color = DyeColor.BLACK;
        }

        SignText toSignText() {
            SignText t = new SignText();
            for (int i = 0; i < LINES; i++) t = t.setMessage(i, Component.literal(lines.get(i)));
            return t.setColor(color).setHasGlowingText(glow);
        }

        static Side of(SignText t) {
            String[] out = new String[LINES];
            Component[] msgs = t.getMessages(false);
            for (int i = 0; i < LINES; i++) out[i] = i < msgs.length ? msgs[i].getString() : "";
            return new Side(List.of(out), t.getColor(), t.hasGlowingText());
        }
    }

    /** A sign's text as the editor sees it. */
    public record Text(Side front, Side back, boolean waxed) {
        public static final Text BLANK_WAXED = new Text(Side.BLANK, Side.BLANK, true);

        public Text {
            if (front == null) front = Side.BLANK;
            if (back == null) back = Side.BLANK;
        }

        /** The first non-blank front line, for the row label; {@code ""} when blank. */
        public String firstLine() {
            for (String l : front.lines()) if (!l.isBlank()) return l;
            for (String l : back.lines()) if (!l.isBlank()) return l;
            return "";
        }
    }

    /** Strip control characters and cut to {@link #MAX_LINE_CHARS}. */
    public static String clampLine(String line) {
        StringBuilder sb = new StringBuilder();
        line.codePoints().filter(cp -> !Character.isISOControl(cp) && cp != 0x00A7)
            .limit(MAX_LINE_CHARS).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    /** The text held in a sign's block-entity NBT; blank (unwaxed) when absent or unreadable. */
    public static Text text(@Nullable CompoundTag nbt) {
        if (nbt == null) return new Text(Side.BLANK, Side.BLANK, false);
        return new Text(side(nbt, TAG_FRONT), side(nbt, TAG_BACK), nbt.getBoolean(TAG_WAXED));
    }

    private static Side side(CompoundTag nbt, String key) {
        if (!nbt.contains(key, Tag.TAG_COMPOUND)) return Side.BLANK;
        return SignText.DIRECT_CODEC.parse(NbtOps.INSTANCE, nbt.getCompound(key))
            .result().map(Side::of).orElse(Side.BLANK);
    }

    /**
     * A copy of {@code nbt} (or a fresh tag) carrying {@code text}. Other keys — a block entity's
     * {@code id}, a mod's extras — are kept.
     */
    public static CompoundTag withText(@Nullable CompoundTag nbt, Text text) {
        CompoundTag out = nbt == null ? new CompoundTag() : nbt.copy();
        out.remove(TAG_FRONT);
        out.remove(TAG_BACK);
        SignText.DIRECT_CODEC.encodeStart(NbtOps.INSTANCE, text.front().toSignText()).result()
            .ifPresent(t -> out.put(TAG_FRONT, t));
        SignText.DIRECT_CODEC.encodeStart(NbtOps.INSTANCE, text.back().toSignText()).result()
            .ifPresent(t -> out.put(TAG_BACK, t));
        out.putBoolean(TAG_WAXED, text.waxed());
        return out;
    }

    /** A copy of {@code nbt} (or a fresh tag) with the wax set — the text is untouched. */
    public static CompoundTag withWaxed(@Nullable CompoundTag nbt, boolean waxed) {
        CompoundTag out = nbt == null ? new CompoundTag() : nbt.copy();
        out.putBoolean(TAG_WAXED, waxed);
        return out;
    }

    /**
     * The block-entity NBT a newly added sign row starts with: what it was captured with, waxed
     * when it carried nothing (so a passenger can't rewrite an editor sign by default).
     */
    @Nullable
    public static CompoundTag defaultNbt(BlockState state, @Nullable CompoundTag captured) {
        if (!isSign(state)) return captured;
        if (captured != null && !captured.isEmpty()) return captured;
        return withWaxed(null, true);
    }

    /** Line width in font pixels for {@code mount}. */
    public static int lineWidth(Mount mount) {
        return isHanging(mount) ? HANGING_LINE_WIDTH : SIGN_LINE_WIDTH;
    }
}
