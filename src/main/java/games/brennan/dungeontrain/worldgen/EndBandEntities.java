package games.brennan.dungeontrain.worldgen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Moves an entity saved in a sampled End chunk to where its blocks land in the display world: the shulkers
 * and elytra frames of an End city, whatever a structure's template carries. Pure NBT, so unit-tested directly.
 */
public final class EndBandEntities {

    private static final String POS = "Pos";

    private EndBandEntities() {}

    /** True if {@code tag} carries the three-part position {@link #moved} needs. */
    public static boolean hasPos(CompoundTag tag) {
        return tag.getList(POS, Tag.TAG_DOUBLE).size() == 3;
    }

    /** X, Y or Z ({@code axis} 0, 1, 2) of a tag {@link #hasPos} accepts. */
    public static double pos(CompoundTag tag, int axis) {
        return tag.getList(POS, Tag.TAG_DOUBLE).getDouble(axis);
    }

    /**
     * A copy of {@code tag} moved by {@code (shiftX, shiftY, 0)}. An attached entity's anchor block moves
     * with it, or an item frame would drop on its first tick. Passengers ride out with their own positions,
     * which their vehicle resets on load.
     */
    public static CompoundTag moved(CompoundTag tag, int shiftX, int shiftY) {
        CompoundTag out = tag.copy();
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(pos(tag, 0) + shiftX));
        pos.add(DoubleTag.valueOf(pos(tag, 1) + shiftY));
        pos.add(DoubleTag.valueOf(pos(tag, 2)));
        out.put(POS, pos);
        if (tag.contains("TileX", Tag.TAG_ANY_NUMERIC)) {
            out.putInt("TileX", tag.getInt("TileX") + shiftX);
            out.putInt("TileY", tag.getInt("TileY") + shiftY);
        }
        return out;
    }
}
