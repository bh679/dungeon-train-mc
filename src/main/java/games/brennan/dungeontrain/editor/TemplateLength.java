package games.brennan.dungeontrain.editor;

import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Re-length a {@link StructureTemplate} along {@code +X} without touching the world: growing pads the
 * far end with air (the size grows, no blocks are added), shrinking crops every block and entity at
 * or past the new length. Height and width are kept.
 *
 * <p>Works on the template's saved NBT — {@code size}, {@code blocks[].pos}, {@code entities[].blockPos}
 * — so the palette and every block entity's data survive untouched.</p>
 */
public final class TemplateLength {

    private TemplateLength() {}

    /** {@code template} re-lengthed to {@code length}; a fresh template, the input is left alone. */
    public static StructureTemplate withLength(StructureTemplate template, int length, HolderGetter<Block> blocks) {
        CompoundTag nbt = withLength(template.save(new CompoundTag()), length);
        StructureTemplate out = new StructureTemplate();
        out.load(blocks, nbt);
        return out;
    }

    /** The NBT form: a copy of {@code tag} whose X size is {@code length}, cropped to it. */
    public static CompoundTag withLength(CompoundTag tag, int length) {
        if (length < 1) throw new IllegalArgumentException("length must be ≥ 1, got " + length);
        CompoundTag out = tag.copy();
        ListTag size = out.getList("size", Tag.TAG_INT);
        if (size.size() == 3) size.set(0, IntTag.valueOf(length));
        out.put("blocks", cropped(out.getList("blocks", Tag.TAG_COMPOUND), "pos", length));
        out.put("entities", cropped(out.getList("entities", Tag.TAG_COMPOUND), "blockPos", length));
        return out;
    }

    private static ListTag cropped(ListTag entries, String posKey, int length) {
        ListTag kept = new ListTag();
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            ListTag pos = entry.getList(posKey, Tag.TAG_INT);
            if (pos.size() == 3 && pos.getInt(0) >= length) continue;
            kept.add(entry);
        }
        return kept;
    }
}
