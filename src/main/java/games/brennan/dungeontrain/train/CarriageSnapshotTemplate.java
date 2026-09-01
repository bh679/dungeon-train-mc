package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
 * <p>Both directions live here. {@link #toTemplateTag} is what a download writes to disk;
 * {@link #fromTemplateTag} is what a stored template goes back up as when the relay has lost the
 * build ({@code BuilderRelayReconcile}). Keeping the pair in one file is what makes the round trip
 * testable, and is why neither drifts.</p>
 *
 * <p>Both reshapings are pure and take no registries, so they can be tested without a world;
 * {@link #toTemplate} and {@link #textOf} are the calls that need a lookup.</p>
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

    /**
     * Reshape template NBT back into a snapshot tag — the exact inverse of {@link #toTemplateTag}.
     *
     * <p>This is the seam that lets a build go <em>back up</em> without being placed in a world.
     * {@link CarriageBlockSnapshot#captureLevel} reads a live volume, which is right after a save and
     * useless afterwards: a build the relay has lost is a file on disk, not blocks standing anywhere.
     * Expanding the stored template is the same re-shaping in reverse — the palette entry a cell's
     * {@code s} came from is written straight back, and a block entry's {@code nbt} is the {@code b}
     * it was.</p>
     *
     * <p>Emitted at {@code v:2} with no {@code ents} key, which is byte-for-byte the shape
     * {@code captureLevel} produces, so a re-upload is indistinguishable on the wire from the upload
     * the save would have made. Entities are not invented from the template's {@code entities} list:
     * every local template is written with {@code withEntities = false}, so there is nothing there.</p>
     *
     * <p>Forgiving cell by cell, exactly as {@link #toTemplateTag} is, and for the same reason: one
     * unreadable block entry costs one block, and refusing over it would lose the build.</p>
     */
    public static CompoundTag fromTemplateTag(CompoundTag template) {
        int[] size = intTriple(template.getList("size", Tag.TAG_INT));
        // No readable size means no volume to declare, and every cell then falls outside it. An empty
        // snapshot rather than a throw: the caller checks what came back, as it does for a build whose
        // file is missing entirely.
        int l = size == null ? 0 : size[0];
        int h = size == null ? 0 : size[1];
        int w = size == null ? 0 : size[2];

        ListTag palette = paletteOf(template);
        ListTag cells = new ListTag();

        ListTag blocks = template.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag entry = blocks.getCompound(i);
            int[] p = intTriple(entry.getList("pos", Tag.TAG_INT));
            if (p == null) continue;
            if (p[0] < 0 || p[1] < 0 || p[2] < 0 || p[0] >= l || p[1] >= h || p[2] >= w) continue;

            int index = entry.getInt("state");
            if (index < 0 || index >= palette.size()) continue;

            CompoundTag cell = new CompoundTag();
            cell.put("p", new IntArrayTag(new int[]{p[0], p[1], p[2]}));
            // Copied, not shared: two cells of one palette entry must not become two references to
            // the same tag, which anything downstream editing a cell would then edit twice.
            cell.put("s", palette.getCompound(index).copy());
            if (entry.contains("nbt", Tag.TAG_COMPOUND)) {
                CompoundTag be = entry.getCompound("nbt").copy();
                // A capture strips these; a template's block entry may carry them.
                be.remove("x");
                be.remove("y");
                be.remove("z");
                cell.put("b", be);
            }
            cells.add(cell);
        }

        CompoundTag out = new CompoundTag();
        out.putInt("v", CarriageBlockSnapshot.FORMAT_VERSION);
        out.putInt("l", l);
        out.putInt("h", h);
        out.putInt("w", w);
        out.put("cells", cells);
        return out;
    }

    /**
     * The authored text a snapshot's block entities carry — what moderation reads.
     *
     * <p>{@link CarriageBlockSnapshot#captureLevel} scrapes this from the live block entities as it
     * walks the volume. A re-upload has no world to walk, so each cell's stored {@code b} tag is
     * loaded back into a block entity and handed to the same {@link CarriageTextScan}, giving a
     * re-upload the same coverage a first upload had. A build's signs and books must not reach the
     * relay unscreened just because they took the other road up.</p>
     *
     * <p>Best-effort per cell, like every other scan on this path: a block entity that will not load
     * contributes nothing rather than failing the upload.</p>
     */
    public static String textOf(CompoundTag snapshot, HolderLookup.Provider registries) {
        HolderGetter<Block> blocks = registries.lookupOrThrow(Registries.BLOCK);
        StringBuilder text = new StringBuilder();
        ListTag cells = snapshot.getList("cells", Tag.TAG_COMPOUND);
        for (int i = 0; i < cells.size(); i++) {
            CompoundTag cell = cells.getCompound(i);
            if (!cell.contains("b", Tag.TAG_COMPOUND) || !cell.contains("s", Tag.TAG_COMPOUND)) continue;
            try {
                BlockState state = NbtUtils.readBlockState(blocks, cell.getCompound("s"));
                BlockEntity be = BlockEntity.loadStatic(BlockPos.ZERO, state, cell.getCompound("b"), registries);
                CarriageTextScan.appendBlockEntity(be, text);
            } catch (Throwable ignored) {
                // One unreadable block entity is one unscanned sign, not a lost build.
            }
        }
        return text.toString();
    }

    /**
     * A template's single palette.
     *
     * <p>Vanilla writes {@code palette} for an ordinary template and {@code palettes} — a list of
     * them — for one saved with block-state variants. Nothing the builder saves is of the second
     * kind, but a template that arrived some other way might be, and the first palette is a complete,
     * valid reading of such a template rather than a reason to refuse it.</p>
     */
    private static ListTag paletteOf(CompoundTag template) {
        if (template.contains("palette", Tag.TAG_LIST)) {
            return template.getList("palette", Tag.TAG_COMPOUND);
        }
        ListTag palettes = template.getList("palettes", Tag.TAG_LIST);
        return palettes.isEmpty() ? new ListTag() : (ListTag) palettes.get(0);
    }

    /** The three ints of a {@code size} / {@code pos} list, or null when it isn't one. */
    private static int[] intTriple(ListTag list) {
        if (list == null || list.size() != 3) return null;
        return new int[]{list.getInt(0), list.getInt(1), list.getInt(2)};
    }

    private static ListTag intList(int x, int y, int z) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(x));
        list.add(IntTag.valueOf(y));
        list.add(IntTag.valueOf(z));
        return list;
    }
}
