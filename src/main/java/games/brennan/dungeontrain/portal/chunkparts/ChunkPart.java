package games.brennan.dungeontrain.portal.chunkparts;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A chunk part template, decoded to a flat grid of block states and block entities.
 *
 * <p>Read straight off the structure NBT rather than through a {@code StructureTemplate}, because
 * {@link ChunkPartPlacer} needs every cell — air included, since air means something different in each
 * layer — and a template's palette is not something it will hand over. The file itself is an ordinary
 * structure file, so the editor saves it the way it saves every other.</p>
 *
 * @param size         the template's extent
 * @param states       one per cell, index {@code (y * size.z + z) * size.x + x}; air where the file had
 *                     nothing
 * @param blockEntities the saved block entity at each cell, or null
 */
public record ChunkPart(Vec3i size, BlockState[] states, CompoundTag[] blockEntities) {

    /** The state at local {@code (x, y, z)}. Never null. */
    public BlockState at(int x, int y, int z) {
        return states[index(x, y, z)];
    }

    /** The block entity NBT at local {@code (x, y, z)}, or null. */
    public CompoundTag blockEntityAt(int x, int y, int z) {
        return blockEntities[index(x, y, z)];
    }

    private int index(int x, int y, int z) {
        return (y * size.getZ() + z) * size.getX() + x;
    }

    /**
     * Decode a structure NBT, or null when it is not one. Only the first palette is read — parts are
     * not authored with palette variants — and a cell whose state is out of range reads as air.
     */
    public static ChunkPart decode(CompoundTag tag, HolderGetter<Block> blocks) {
        ListTag sizeTag = tag.getList("size", Tag.TAG_INT);
        if (sizeTag.size() != 3) return null;
        Vec3i size = new Vec3i(sizeTag.getInt(0), sizeTag.getInt(1), sizeTag.getInt(2));
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) return null;

        ListTag paletteTag = tag.contains("palettes", Tag.TAG_LIST)
            ? tag.getList("palettes", Tag.TAG_LIST).getList(0)
            : tag.getList("palette", Tag.TAG_COMPOUND);
        BlockState[] palette = new BlockState[paletteTag.size()];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = NbtUtils.readBlockState(blocks, paletteTag.getCompound(i));
        }

        int cells = size.getX() * size.getY() * size.getZ();
        BlockState[] states = new BlockState[cells];
        java.util.Arrays.fill(states, Blocks.AIR.defaultBlockState());
        CompoundTag[] blockEntities = new CompoundTag[cells];
        ChunkPart part = new ChunkPart(size, states, blockEntities);

        ListTag blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag entry = blocksTag.getCompound(i);
            ListTag pos = entry.getList("pos", Tag.TAG_INT);
            if (pos.size() != 3) continue;
            int x = pos.getInt(0), y = pos.getInt(1), z = pos.getInt(2);
            if (x < 0 || y < 0 || z < 0 || x >= size.getX() || y >= size.getY() || z >= size.getZ()) continue;
            int state = entry.getInt("state");
            if (state < 0 || state >= palette.length) continue;
            int index = part.index(x, y, z);
            states[index] = palette[state];
            if (entry.contains("nbt", Tag.TAG_COMPOUND)) blockEntities[index] = entry.getCompound("nbt");
        }
        return part;
    }
}
