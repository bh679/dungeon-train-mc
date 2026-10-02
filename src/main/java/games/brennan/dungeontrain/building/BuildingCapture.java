package games.brennan.dungeontrain.building;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Turns a captured plot into a building template that sits in the world the way the shipped ones do.
 *
 * <p>The Lost City convention ({@code scripts/lost-city/README.md}): <b>air only inside the envelope</b>.
 * Rooms carry explicit air, so terrain a hill pushes into the footprint is carved out of them; the sky above
 * the roof and the pad margin carry nothing, so the biome's plants stand on the plaza and hills lean in. A
 * plot capture stores every air cell, so this trims them with a column rule: an air cell is kept only below
 * the highest non-air block of its own column. Columns with nothing in them keep nothing.</p>
 *
 * <p>Works on the saved NBT — no level — so a round trip of a shipped building, whose sky was never stored,
 * saves back the same cells.</p>
 */
public final class BuildingCapture {

    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

    private BuildingCapture() {}

    /** A copy of {@code structure} with the air above each column's top block (and in empty columns) dropped. */
    public static CompoundTag trimOuterAir(CompoundTag structure) {
        CompoundTag out = structure.copy();
        ListTag palette = out.getList("palette", Tag.TAG_COMPOUND);
        boolean[] isAir = new boolean[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            isAir[i] = AIR.contains(palette.getCompound(i).getString("Name"));
        }
        ListTag blocks = out.getList("blocks", Tag.TAG_COMPOUND);
        Map<Long, Integer> columnTop = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            if (airAt(block, isAir)) continue;
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            columnTop.merge(column(pos), pos.getInt(1), Math::max);
        }
        ListTag kept = new ListTag();
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            if (airAt(block, isAir)) {
                ListTag pos = block.getList("pos", Tag.TAG_INT);
                Integer top = columnTop.get(column(pos));
                if (top == null || pos.getInt(1) >= top) continue;
            }
            kept.add(block);
        }
        out.put("blocks", kept);
        return out;
    }

    private static boolean airAt(CompoundTag block, boolean[] isAir) {
        int state = block.getInt("state");
        return state >= 0 && state < isAir.length && isAir[state];
    }

    private static long column(ListTag pos) {
        return ((long) pos.getInt(0) << 32) | (pos.getInt(2) & 0xFFFF_FFFFL);
    }
}
