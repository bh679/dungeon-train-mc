package games.brennan.dungeontrain.worldgen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which End structures a BetterEnd band sample builds, and how their entities reach the display world. */
class EndBandStructuresTest {

    private static ResourceLocation id(String ns, String path) {
        return ResourceLocation.fromNamespaceAndPath(ns, path);
    }

    @Test
    @DisplayName("Eternal portals and DT's own structures are left out; the rest are built")
    void exclusions() {
        CycleLayout.Style better = CycleLayout.Style.BETTER;
        assertTrue(EndBandStructures.isExcluded(id("betterend", "eternal_portal"), better));
        assertTrue(EndBandStructures.isExcluded(id("dungeontrain", "end_city"), better));
        assertFalse(EndBandStructures.isExcluded(id("betterend", "end_bridge"), better));
        assertFalse(EndBandStructures.isExcluded(id("betterend", "end_village"), better));
        assertFalse(EndBandStructures.isExcluded(id("minecraft", "end_city"), better));
    }

    @Test
    @DisplayName("a Biomes O' Plenty pass builds only vanilla and BoP structures")
    void bopExclusions() {
        CycleLayout.Style bop = CycleLayout.Style.BOP;
        assertFalse(EndBandStructures.isExcluded(id("minecraft", "end_city"), bop));
        assertFalse(EndBandStructures.isExcluded(id("biomesoplenty", "anything"), bop));
        assertTrue(EndBandStructures.isExcluded(id("betterend", "end_bridge"), bop), "listed on vanilla End biomes");
        assertTrue(EndBandStructures.isExcluded(id("betterend", "eternal_portal"), bop));
        assertTrue(EndBandStructures.isExcluded(id("dungeontrain", "end_city"), bop));
        assertTrue(EndBandStructures.isExcluded(id("othermod", "tower"), bop));
    }

    @Test
    @DisplayName("only a Biomes O' Plenty pass spaces End cities like the vanilla End band")
    void bandCitySpacing() {
        assertTrue(EndBandStructures.usesBandCitySpacing(id("minecraft", "end_cities"), CycleLayout.Style.BOP));
        assertFalse(EndBandStructures.usesBandCitySpacing(id("minecraft", "end_cities"), CycleLayout.Style.BETTER));
        assertFalse(EndBandStructures.usesBandCitySpacing(id("biomesoplenty", "anything"), CycleLayout.Style.BOP));
    }

    @Test
    @DisplayName("a start reaches a chunk only when its box touches it")
    void reach() {
        ChunkPos chunk = new ChunkPos(1, 1);    // blocks 16..31
        assertTrue(EndBandStructures.reaches(chunk, 0, 0, 16, 16), "touches the corner block");
        assertTrue(EndBandStructures.reaches(chunk, 20, 20, 25, 25), "inside");
        assertTrue(EndBandStructures.reaches(chunk, -100, 20, 100, 21), "a long bridge crossing it");
        assertFalse(EndBandStructures.reaches(chunk, 0, 0, 15, 31), "stops one block short in X");
        assertFalse(EndBandStructures.reaches(chunk, 16, 32, 31, 40), "starts one block past in Z");
    }

    private static CompoundTag entityAt(double x, double y, double z) {
        CompoundTag tag = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        tag.put("Pos", pos);
        return tag;
    }

    @Test
    @DisplayName("an entity moves with the blocks, and leaves the original tag alone")
    void movesPosition() {
        CompoundTag source = entityAt(16_005.5, 70.0, 3.5);
        CompoundTag moved = EndBandEntities.moved(source, -16_000, 8);
        assertEquals(5.5, EndBandEntities.pos(moved, 0));
        assertEquals(78.0, EndBandEntities.pos(moved, 1));
        assertEquals(3.5, EndBandEntities.pos(moved, 2));
        assertEquals(16_005.5, EndBandEntities.pos(source, 0));
    }

    @Test
    @DisplayName("an item frame's anchor block moves with it")
    void movesAnchor() {
        CompoundTag frame = entityAt(16_005.5, 70.0, 3.5);
        frame.putInt("TileX", 16_005);
        frame.putInt("TileY", 70);
        frame.putInt("TileZ", 3);
        CompoundTag moved = EndBandEntities.moved(frame, -16_000, 8);
        assertEquals(5, moved.getInt("TileX"));
        assertEquals(78, moved.getInt("TileY"));
        assertEquals(3, moved.getInt("TileZ"));
    }

    @Test
    @DisplayName("a tag with no position is refused")
    void noPosition() {
        assertFalse(EndBandEntities.hasPos(new CompoundTag()));
        assertTrue(EndBandEntities.hasPos(entityAt(0, 0, 0)));
    }
}
