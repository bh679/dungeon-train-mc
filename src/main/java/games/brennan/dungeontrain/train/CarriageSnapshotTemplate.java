package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.template.TemplateDecor;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
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
 * <p><b>What a local save would keep comes across; nothing else does.</b> A v2 blob's {@code ents}
 * list becomes the template's {@code entities}, filtered through {@link TemplateDecor#carries} —
 * the same rule {@code TemplateDecor.keepOnlyDecor} applies as a template is written. Dropping them entirely would produce a template
 * unlike anything a save writes: a picture the author hung, and saved, and uploaded, absent on the
 * way back. Keeping the rest would be the same mistake in the other direction, because a local save
 * does not keep it — {@code TemplateDecor.keepOnlyDecor} strips every other entity as the template
 * is written, on the grounds that mobs are authored as per-cell variant entries and the contents
 * pass owns armour stands and end crystals. A downloaded template must hold what a saved one holds,
 * or a build gains on the way back something its own author's file never had.</p>
 *
 * <p>The <b>blob</b> still carries them all, and should: the same {@code ents} list is what an
 * in-play lease spawns through {@link CarriageEntitySnapshot#spawn}, and what the web viewer draws
 * its entity cards from. The narrowing belongs here, at the blob → template seam, not at capture.</p>
 *
 * <p>The two formats describe an entity in nearly the same terms: an {@code ents} entry's {@code n}
 * is {@code saveAsPassenger} output with {@code Pos}, {@code Motion} and every UUID already stripped
 * ({@link CarriageEntitySnapshot}), which is exactly what a template entity's {@code nbt} is, and its
 * {@code p} is the offset from the build's origin, which is exactly what {@code pos} is. Only
 * {@code blockPos} — the containing block, which vanilla stores alongside — has to be derived.</p>
 *
 * <p>{@link #toTemplateTag} is pure and takes no registries, so the reshaping can be tested without
 * a world; {@link #toTemplate} is the one call that needs a block lookup.</p>
 */
public final class CarriageSnapshotTemplate {

    private CarriageSnapshotTemplate() {}

    /**
     * Reshape a snapshot tag ({@code {v,l,h,w,cells:[{p,s,b?}],ents?:[{p,n}]}}) into template NBT
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
        // Empty rather than omitted when the blob carries no entities, so the tag is shaped exactly
        // like one vanilla saves.
        out.put("entities", entities(snapshot));
        return out;
    }

    /**
     * The template's {@code entities} list, built from the blob's {@code ents}.
     *
     * <p>Forgiving per entry, exactly as the cell loop is and for the same reason: one entity that
     * will not reshape costs that entity, and refusing the download over it would cost the build.
     * An entry with no stored NBT is skipped rather than written as an entity with no type — vanilla
     * reads {@code nbt.id} to decide what to spawn, and an entry without one is a crash waiting on
     * whoever next stamps the template. Anything that is not decor is skipped too, so the list
     * matches what a local save writes.</p>
     */
    private static ListTag entities(CompoundTag snapshot) {
        ListTag out = new ListTag();
        ListTag ents = snapshot.getList("ents", Tag.TAG_COMPOUND);
        for (int i = 0; i < ents.size(); i++) {
            CompoundTag ent = ents.getCompound(i);
            if (!ent.contains("n", Tag.TAG_COMPOUND)) continue;
            CompoundTag nbt = ent.getCompound("n");
            if (!nbt.contains("id", Tag.TAG_STRING)) continue;
            // What a local save would have kept — see the class javadoc. A mob here is not a build's
            // decoration, it is a mob somebody left standing in the plot, and no stamp path puts one
            // back.
            if (!TemplateDecor.carries(nbt)) continue;
            ListTag p = ent.getList("p", Tag.TAG_DOUBLE);
            if (p.size() != 3) continue;
            double x = p.getDouble(0), y = p.getDouble(1), z = p.getDouble(2);

            CompoundTag entry = new CompoundTag();
            entry.put("pos", doubleList(x, y, z));
            // Floor, not round: `blockPos` is the block an entity stands IN, and vanilla's own
            // fillFromWorld derives it the same way.
            entry.put("blockPos", intList(
                    Mth.floor(x), Mth.floor(y), Mth.floor(z)));
            entry.put("nbt", nbt);
            out.add(entry);
        }
        return out;
    }

    private static ListTag doubleList(double x, double y, double z) {
        ListTag list = new ListTag();
        list.add(DoubleTag.valueOf(x));
        list.add(DoubleTag.valueOf(y));
        list.add(DoubleTag.valueOf(z));
        return list;
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
