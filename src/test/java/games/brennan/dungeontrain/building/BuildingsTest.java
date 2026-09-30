package games.brennan.dungeontrain.building;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BuildingsTest {

    @Test
    @DisplayName("a template id names a building only under DT's two building prefixes")
    void nameOf() {
        assertEquals(Optional.of("office_tower"), Buildings.nameOf(rl("dungeontrain", "lost_city/office_tower")));
        assertEquals(Optional.of("my_shop"), Buildings.nameOf(rl("dungeontrain", "buildings/my_shop")));
        assertEquals(Optional.empty(), Buildings.nameOf(rl("big_lost_city", "lost_city/warehouse")));
        assertEquals(Optional.empty(), Buildings.nameOf(rl("dungeontrain", "chunk_frames/x")));
        assertEquals(Optional.empty(), Buildings.nameOf(rl("dungeontrain", "lost_city/nested/deep")));
        assertEquals(Optional.empty(), Buildings.nameOf(null));
    }

    @Test
    @DisplayName("sizes clamp into the building caps on every axis")
    void clamp() {
        assertEquals(new Vec3i(64, 159, 4), Buildings.clamp(new Vec3i(200, 300, 1)));
        assertEquals(new Vec3i(16, 24, 16), Buildings.clamp(Buildings.DEFAULT_SIZE));
    }

    @Test
    @DisplayName("air is kept only below each column's top block; empty columns keep nothing")
    void trimOuterAir() {
        // palette: 0 air, 1 stone. Column (0,0): stone at y=0 and y=3, air at 1, 2, 4. Column (1,0): all air.
        CompoundTag structure = structure(new String[] {"minecraft:air", "minecraft:stone"}, new int[][] {
            {0, 0, 0, 1}, {0, 1, 0, 0}, {0, 2, 0, 0}, {0, 3, 0, 1}, {0, 4, 0, 0},
            {1, 0, 0, 0}, {1, 1, 0, 0},
        });
        Set<String> kept = cells(BuildingCapture.trimOuterAir(structure));
        assertEquals(Set.of("0,0,0", "0,1,0", "0,2,0", "0,3,0"), kept);
    }

    @Test
    @DisplayName("a template with no stored sky trims to itself — a shipped building round-trips")
    void trimIsStableOnShippedShape() {
        CompoundTag structure = structure(new String[] {"minecraft:air", "minecraft:stone"}, new int[][] {
            {0, 0, 0, 1}, {0, 1, 0, 0}, {0, 2, 0, 1},
        });
        CompoundTag once = BuildingCapture.trimOuterAir(structure);
        assertEquals(cells(structure), cells(once));
        assertEquals(cells(once), cells(BuildingCapture.trimOuterAir(once)));
    }

    @Test
    @DisplayName("the weighted pick lands each roll on its building's share")
    void indexFor() {
        int[] weights = {3, 1, 2};
        assertEquals(0, BuildingWorldgen.indexFor(weights, 0));
        assertEquals(0, BuildingWorldgen.indexFor(weights, 2));
        assertEquals(1, BuildingWorldgen.indexFor(weights, 3));
        assertEquals(2, BuildingWorldgen.indexFor(weights, 4));
        assertEquals(2, BuildingWorldgen.indexFor(weights, 5));
    }

    @Test
    @DisplayName("a sidecar's weight is clamped, and a missing one is the default")
    void metaParse() {
        assertEquals(7, BuildingMeta.parse("{\"weight\": 7}").weight());
        assertEquals(BuildingMeta.MAX_WEIGHT, BuildingMeta.parse("{\"weight\": 999}").weight());
        assertEquals(BuildingMeta.MIN_WEIGHT, BuildingMeta.parse("{\"weight\": -4}").weight());
        assertEquals(BuildingMeta.DEFAULT_WEIGHT, BuildingMeta.parse("{}").weight());
        assertEquals(5, BuildingMeta.parse(BuildingMeta.DEFAULT.withWeight(5).toJson()).weight());
    }

    @Test
    @DisplayName("an official building goes by its template's name without Big Lost City's lt suffix")
    void lostCityDisplayName() {
        assertEquals("tall_skyscraper", LostCityReferences.displayName(rl("big_lost_city", "tall_skyscraperlt")));
        assertEquals("warehouse", LostCityReferences.displayName(rl("big_lost_city", "warehouse")));
        assertEquals("lt", LostCityReferences.displayName(rl("big_lost_city", "lt")));
    }

    private static ResourceLocation rl(String ns, String path) {
        return ResourceLocation.fromNamespaceAndPath(ns, path);
    }

    private static CompoundTag structure(String[] palette, int[][] blocks) {
        CompoundTag tag = new CompoundTag();
        ListTag pal = new ListTag();
        for (String name : palette) {
            CompoundTag state = new CompoundTag();
            state.put("Name", StringTag.valueOf(name));
            pal.add(state);
        }
        tag.put("palette", pal);
        ListTag list = new ListTag();
        for (int[] b : blocks) {
            CompoundTag block = new CompoundTag();
            ListTag pos = new ListTag();
            pos.add(IntTag.valueOf(b[0]));
            pos.add(IntTag.valueOf(b[1]));
            pos.add(IntTag.valueOf(b[2]));
            block.put("pos", pos);
            block.putInt("state", b[3]);
            list.add(block);
        }
        tag.put("blocks", list);
        return tag;
    }

    private static Set<String> cells(CompoundTag structure) {
        Set<String> out = new HashSet<>();
        ListTag blocks = structure.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            ListTag pos = blocks.getCompound(i).getList("pos", Tag.TAG_INT);
            out.add(pos.getInt(0) + "," + pos.getInt(1) + "," + pos.getInt(2));
        }
        return out;
    }
}
