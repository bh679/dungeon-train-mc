package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.builder.TileShell;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A lighter copy of a build, for a picture only — what a preview becomes when the build as it is
 * will not fit on the wire.
 *
 * <p>A tile is a hundred pixels wide and is drawn from block models alone, so most of a big build is
 * weight the picture never shows: the air its box was saved with, chest contents and other block NBT,
 * entities, and every block behind its outside walls. All of that goes ({@link TileShell} keeps the
 * outside, the same cut the tile bake makes for a build too big to bake whole). If the skin alone is
 * still too heavy, the build is drawn coarser: each {@code f×f×f} group of cells becomes one block of
 * its most common kind.</p>
 *
 * <p>The data sheet still tells the truth: the slimmed tag carries the build's real block count and
 * size under {@link #PREVIEW_KEY}.</p>
 */
public final class PreviewSlimmer {

    /** Where the real numbers ride: {@code {blocks:int, size:[x,y,z], factor:int}}. */
    public static final String PREVIEW_KEY = "dt_preview";

    /** The coarsest a picture is drawn. Past this a build keeps its name plate. */
    public static final int MAX_FACTOR = 4;

    private PreviewSlimmer() {}

    /** A build read once, ready to be slimmed at any factor. */
    public static final class Source {
        private final Map<BlockPos, Integer> cells;
        private final ListTag palette;
        private final ListTag size;
        private final Tag dataVersion;

        private Source(Map<BlockPos, Integer> cells, ListTag palette, ListTag size, Tag dataVersion) {
            this.cells = cells;
            this.palette = palette;
            this.size = size;
            this.dataVersion = dataVersion;
        }

        /** Non-air blocks in the whole build — what the sheet counts. */
        public int blocks() {
            return cells.size();
        }

        /** The picture at {@code factor} (1 = full detail) as structure-template NBT. */
        public CompoundTag slim(int factor) {
            Map<BlockPos, Integer> picture = picture(cells, factor);
            CompoundTag out = new CompoundTag();
            if (dataVersion != null) out.put("DataVersion", dataVersion.copy());
            out.put("palette", palette.copy());
            ListTag blocks = new ListTag();
            int maxX = 0, maxY = 0, maxZ = 0;
            for (Map.Entry<BlockPos, Integer> e : picture.entrySet()) {
                BlockPos p = e.getKey();
                maxX = Math.max(maxX, p.getX());
                maxY = Math.max(maxY, p.getY());
                maxZ = Math.max(maxZ, p.getZ());
                CompoundTag block = new CompoundTag();
                block.put("pos", intList(p.getX(), p.getY(), p.getZ()));
                block.putInt("state", e.getValue());
                blocks.add(block);
            }
            out.put("blocks", blocks);
            out.put("entities", new ListTag());
            out.put("size", factor == 1 ? size.copy() : intList(maxX + 1, maxY + 1, maxZ + 1));
            CompoundTag real = new CompoundTag();
            real.putInt("blocks", cells.size());
            real.put("size", size.copy());
            real.putInt("factor", factor);
            out.put(PREVIEW_KEY, real);
            return out;
        }
    }

    /**
     * Read a structure template's first palette and its non-air blocks, or null when there is
     * nothing to read. Server thread: resolving the palette reads the block registry.
     */
    public static Source read(CompoundTag template, HolderGetter<Block> registry) {
        if (template == null || registry == null) return null;
        ListTag palette = template.contains("palettes", Tag.TAG_LIST)
            ? template.getList("palettes", Tag.TAG_LIST).getList(0)
            : template.getList("palette", Tag.TAG_COMPOUND);
        ListTag blocks = template.getList("blocks", Tag.TAG_COMPOUND);
        if (palette.isEmpty() || blocks.isEmpty()) return null;
        boolean[] air = new boolean[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            BlockState state = NbtUtils.readBlockState(registry, palette.getCompound(i));
            air[i] = state.isAir();
        }
        Map<BlockPos, Integer> cells = new LinkedHashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            int state = block.getInt("state");
            if (state < 0 || state >= air.length || air[state]) continue;
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            if (pos.size() < 3) continue;
            cells.put(new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)), state);
        }
        if (cells.isEmpty()) return null;
        return new Source(cells, palette, template.getList("size", Tag.TAG_INT), template.get("DataVersion"));
    }

    /** What the picture keeps at {@code factor}: the outside of the build, drawn that coarse. */
    static Map<BlockPos, Integer> picture(Map<BlockPos, Integer> cells, int factor) {
        return TileShell.of(factor <= 1 ? cells : downsample(cells, factor));
    }

    /**
     * Each {@code f×f×f} group of cells as one cell of its most common state; ties go to the lower
     * palette index so the same build always draws the same way.
     */
    static Map<BlockPos, Integer> downsample(Map<BlockPos, Integer> cells, int f) {
        Map<BlockPos, Map<Integer, Integer>> votes = new HashMap<>();
        for (Map.Entry<BlockPos, Integer> e : cells.entrySet()) {
            BlockPos p = e.getKey();
            BlockPos group = new BlockPos(Math.floorDiv(p.getX(), f), Math.floorDiv(p.getY(), f),
                Math.floorDiv(p.getZ(), f));
            votes.computeIfAbsent(group, k -> new HashMap<>()).merge(e.getValue(), 1, Integer::sum);
        }
        Map<BlockPos, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, Map<Integer, Integer>> e : votes.entrySet()) {
            int best = -1;
            int bestCount = 0;
            for (Map.Entry<Integer, Integer> v : e.getValue().entrySet()) {
                if (v.getValue() > bestCount || (v.getValue() == bestCount && v.getKey() < best)) {
                    best = v.getKey();
                    bestCount = v.getValue();
                }
            }
            out.put(e.getKey(), best);
        }
        return out;
    }

    private static ListTag intList(int x, int y, int z) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(x));
        list.add(IntTag.valueOf(y));
        list.add(IntTag.valueOf(z));
        return list;
    }
}
