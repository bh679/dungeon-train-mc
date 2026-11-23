package games.brennan.dungeontrain.template;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps flowing fluid out of a saved template: water and lava cells that are not a source — the
 * spread of a placed bucket, and the falling column under it — are taken out of the capture, so a
 * template carries only the source blocks the author placed and every stamp grows its own flow.
 *
 * <p>Flow baked into a template is a snapshot of a fluid mid-spread. It stamps as dead water that
 * vanishes on the first tick wherever no source feeds it, and as a puddle the author never meant
 * wherever one does. Sources stay, and so does every waterlogged block: in Java Edition flowing
 * water never sets {@code waterlogged}, and a waterlogged block is its own source.</p>
 *
 * <p>Pure NBT over what {@code StructureTemplate.save} writes, in the shape of
 * {@link TemplateDecor#filterEntities}, so it tests without a world. What a dropped cell becomes
 * follows the capture's own idea of an empty cell:</p>
 * <ul>
 *   <li>{@code airSaved = false} — a capture that ignores AIR never writes an air cell, so the
 *       entry is removed and the cell is empty the same way every other air cell in it is;</li>
 *   <li>{@code airSaved = true} — a capture against {@code STRUCTURE_VOID} or nothing writes air
 *       explicitly, and a missing entry would mean "leave whatever is there" on stamp, so the entry
 *       is pointed at air instead.</li>
 * </ul>
 */
public final class TemplateFluids {

    static final String WATER = "minecraft:water";
    static final String LAVA = "minecraft:lava";
    static final String AIR = "minecraft:air";
    private static final String SOURCE_LEVEL = "0";

    private TemplateFluids() {}

    /**
     * Whether one palette entry is a non-source fluid block: water or lava whose {@code level} is
     * not 0 (1–7 flowing, 8 and up falling). A fluid block saved without a level is a source.
     */
    public static boolean isFlowing(CompoundTag paletteEntry) {
        String name = paletteEntry.getString("Name");
        if (!WATER.equals(name) && !LAVA.equals(name)) return false;
        CompoundTag props = paletteEntry.getCompound("Properties");
        if (!props.contains("level", Tag.TAG_STRING)) return false;
        return !SOURCE_LEVEL.equals(props.getString("level"));
    }

    /**
     * Take every non-source fluid cell out of a saved template, in place.
     *
     * @param templateTag what {@code StructureTemplate.save} wrote — a fresh copy the caller owns
     * @param airSaved    whether the capture writes air cells (see the class javadoc)
     * @return whether anything changed — i.e. whether {@code templateTag} needs reloading
     */
    public static boolean dropFlowing(CompoundTag templateTag, boolean airSaved) {
        List<ListTag> palettes = palettesOf(templateTag);
        if (palettes.isEmpty() || !templateTag.contains("blocks", Tag.TAG_LIST)) return false;

        Set<Integer> flowing = flowingIndices(palettes);
        if (flowing.isEmpty()) return false;

        ListTag blocks = templateTag.getList("blocks", Tag.TAG_COMPOUND);
        int air = -1; // resolved on the first dropped cell, and only when air is saved
        ListTag kept = new ListTag();
        boolean changed = false;
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            if (!flowing.contains(block.getInt("state"))) {
                kept.add(block);
                continue;
            }
            changed = true;
            if (!airSaved) continue;
            if (air < 0) air = airIndex(palettes);
            CompoundTag asAir = new CompoundTag();
            asAir.put("pos", block.get("pos").copy());
            asAir.putInt("state", air);
            kept.add(asAir);
        }
        if (changed) templateTag.put("blocks", kept);
        return changed;
    }

    /** The single {@code palette}, or each of the {@code palettes}; empty when there is neither. */
    private static List<ListTag> palettesOf(CompoundTag tag) {
        List<ListTag> out = new ArrayList<>();
        if (tag.contains("palettes", Tag.TAG_LIST)) {
            ListTag all = tag.getList("palettes", Tag.TAG_LIST);
            for (int i = 0; i < all.size(); i++) out.add(all.getList(i));
        } else if (tag.contains("palette", Tag.TAG_LIST)) {
            out.add(tag.getList("palette", Tag.TAG_COMPOUND));
        }
        return out;
    }

    /**
     * The palette indices that are flowing fluid in <i>every</i> palette — a multi-palette template
     * picks one palette per placement, and a cell is only safe to drop if it is flow whichever one
     * is picked.
     */
    private static Set<Integer> flowingIndices(List<ListTag> palettes) {
        Set<Integer> out = new HashSet<>();
        ListTag first = palettes.get(0);
        for (int i = 0; i < first.size(); i++) {
            boolean everywhere = true;
            for (ListTag palette : palettes) {
                if (i >= palette.size() || !isFlowing(palette.getCompound(i))) {
                    everywhere = false;
                    break;
                }
            }
            if (everywhere) out.add(i);
        }
        return out;
    }

    /**
     * The palette index of plain air — the existing one when the first palette has it, else a new
     * entry appended to every palette so the index means air in each.
     */
    private static int airIndex(List<ListTag> palettes) {
        ListTag first = palettes.get(0);
        for (int i = 0; i < first.size(); i++) {
            CompoundTag entry = first.getCompound(i);
            if (AIR.equals(entry.getString("Name")) && isAirEverywhere(palettes, i)) return i;
        }
        int index = first.size();
        for (ListTag palette : palettes) {
            while (palette.size() < index) palette.add(airEntry());
            palette.add(airEntry());
        }
        return index;
    }

    private static boolean isAirEverywhere(List<ListTag> palettes, int i) {
        for (ListTag palette : palettes) {
            if (i >= palette.size() || !AIR.equals(palette.getCompound(i).getString("Name"))) return false;
        }
        return true;
    }

    private static CompoundTag airEntry() {
        CompoundTag air = new CompoundTag();
        air.putString("Name", AIR);
        return air;
    }
}
