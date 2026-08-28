package games.brennan.dungeontrain.train;

import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns a relay {@link CarriageBlockSnapshot} blob back into a vanilla {@link StructureTemplate} —
 * the form every template store on disk holds.
 *
 * <p>This is the seam that lets a build come <em>back</em>. The relay stores what a carriage's blocks
 * were, in the snapshot's own compact form; the editors store templates. Both describe the same
 * volume, and closely enough that this is a re-shaping rather than a translation: a cell's {@code s}
 * is already {@link net.minecraft.nbt.NbtUtils#writeBlockState} output, which is exactly what a
 * palette entry is, and its {@code b} is a block-entity tag with {@code x/y/z} stripped, which is
 * exactly what a block entry's {@code nbt} is. What is actually done here is palette
 * de-duplication.</p>
 *
 * <p><b>Air is absent from both</b>, and means the same thing in both. A snapshot stores only
 * non-air cells; a builder save captures with {@code fillFromWorld(…, Blocks.AIR)}, which excludes
 * air for the same reason. So a round trip through the relay neither gains nor loses empty space.
 * (The one exception is a tunnel, whose editor captures against {@code STRUCTURE_VOID} and so keeps
 * its air — but the snapshot dropped that air at <em>upload</em> time, long before this code, so
 * there is nothing here that could put it back.)</p>
 *
 * <p><b>Entities are dropped.</b> A v2 blob may carry an {@code ents} list, but every local template
 * is captured with {@code withEntities = false}, so carrying them across would produce a template
 * unlike anything a save writes.</p>
 *
 * <p>{@link #toTemplateTag} is pure and takes no registries, so the reshaping can be tested without
 * a world; {@link #toTemplate} is the one call that needs a block lookup.</p>
 */
public final class CarriageSnapshotTemplate {

    private CarriageSnapshotTemplate() {}

    /**
     * Reshape a snapshot tag ({@code {v,l,h,w,cells:[{p,s,b?}]}}) into template NBT
     * ({@code {size,palette,blocks,entities}}).
     *
     * <p>Forgiving, cell by cell: one malformed entry — no position, no block state, a position
     * outside the declared volume — is skipped rather than failing the whole build. A blob that
     * reaches here has already survived a round trip through the relay and a gzip decode, so a bad
     * cell means one lost block, and refusing the download over it would lose the other thousands.</p>
     */
    public static CompoundTag toTemplateTag(CompoundTag snapshot) {
        int l = snapshot.getInt("l");
        int h = snapshot.getInt("h");
        int w = snapshot.getInt("w");

        ListTag palette = new ListTag();
        // The state tag itself is the key: NBT compounds compare by value, so two cells of the same
        // block state collapse onto one palette entry without any string round trip.
        Map<CompoundTag, Integer> indices = new HashMap<>();
        ListTag blocks = new ListTag();

        ListTag cells = snapshot.getList("cells", Tag.TAG_COMPOUND);
        for (int i = 0; i < cells.size(); i++) {
            CompoundTag cell = cells.getCompound(i);
            int[] p = cell.getIntArray("p");
            if (p.length != 3 || !cell.contains("s", Tag.TAG_COMPOUND)) continue;
            if (p[0] < 0 || p[1] < 0 || p[2] < 0 || p[0] >= l || p[1] >= h || p[2] >= w) continue;

            CompoundTag state = cell.getCompound("s");
            Integer index = indices.get(state);
            if (index == null) {
                index = palette.size();
                indices.put(state, index);
                palette.add(state);
            }

            CompoundTag entry = new CompoundTag();
            entry.put("pos", intList(p[0], p[1], p[2]));
            entry.putInt("state", index);
            if (cell.contains("b", Tag.TAG_COMPOUND)) {
                entry.put("nbt", cell.getCompound("b"));
            }
            blocks.add(entry);
        }

        CompoundTag out = new CompoundTag();
        out.put("size", intList(l, h, w));
        out.put("palette", palette);
        out.put("blocks", blocks);
        // Written empty rather than omitted, so the tag is shaped exactly like one vanilla saves.
        out.put("entities", new ListTag());
        return out;
    }

    /** As {@link #toTemplateTag}, loaded into a live {@link StructureTemplate}. */
    public static StructureTemplate toTemplate(CompoundTag snapshot, HolderGetter<Block> blocks) {
        StructureTemplate template = new StructureTemplate();
        template.load(blocks, toTemplateTag(snapshot));
        return template;
    }

    private static ListTag intList(int x, int y, int z) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(x));
        list.add(IntTag.valueOf(y));
        list.add(IntTag.valueOf(z));
        return list;
    }
}
