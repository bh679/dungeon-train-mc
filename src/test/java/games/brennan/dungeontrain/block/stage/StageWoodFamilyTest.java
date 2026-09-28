package games.brennan.dungeontrain.block.stage;

import games.brennan.dungeontrain.block.stage.StageWoodFamily.WoodKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wood-family table ported from {@code scripts/parts/clone-wood-stage.py}. */
final class StageWoodFamilyTest {

    @Test
    @DisplayName("overworld woods spell every kind the vanilla way")
    void overworld() {
        assertEquals("minecraft:stripped_dark_oak_log", StageWoodFamily.DARK_OAK.block(WoodKind.STRIPPED_LOG));
        assertEquals("minecraft:cherry_fence_gate", StageWoodFamily.CHERRY.block(WoodKind.FENCE_GATE));
        assertEquals("minecraft:birch_trapdoor", StageWoodFamily.BIRCH.block(WoodKind.TRAPDOOR));
        assertEquals("minecraft:cherry_leaves", StageWoodFamily.CHERRY.block(WoodKind.LEAVES));
        assertEquals("minecraft:mangrove_leaves", StageWoodFamily.MANGROVE.block(WoodKind.LEAVES));
    }

    @Test
    @DisplayName("leaves: nether woods use their wart block; bamboo (and mosaic) borrow jungle leaves")
    void leaves() {
        assertEquals("minecraft:nether_wart_block", StageWoodFamily.CRIMSON.block(WoodKind.LEAVES));
        assertEquals("minecraft:warped_wart_block", StageWoodFamily.WARPED.block(WoodKind.LEAVES));
        assertEquals("minecraft:jungle_leaves", StageWoodFamily.BAMBOO.block(WoodKind.LEAVES));
        assertEquals("minecraft:jungle_leaves", StageWoodFamily.BAMBOO_MOSAIC.block(WoodKind.LEAVES));
        assertEquals(Optional.of(StageWoodFamily.CHERRY), StageWoodFamily.owning("minecraft:cherry_leaves"));
        assertEquals(Optional.of(StageWoodFamily.JUNGLE), StageWoodFamily.owning("minecraft:jungle_leaves"));
        assertEquals(Optional.of(StageWoodFamily.CRIMSON), StageWoodFamily.owning("minecraft:nether_wart_block"));
    }

    @Test
    @DisplayName("nether woods use stem/hyphae; bamboo has no log; mosaic swaps planks/stairs/slab and inverts pillars")
    void specialFamilies() {
        assertEquals("minecraft:crimson_stem", StageWoodFamily.CRIMSON.block(WoodKind.LOG));
        assertEquals("minecraft:stripped_warped_hyphae", StageWoodFamily.WARPED.block(WoodKind.STRIPPED_WOOD));
        assertEquals("minecraft:warped_planks", StageWoodFamily.WARPED.block(WoodKind.PLANKS));
        assertEquals("minecraft:bamboo_block", StageWoodFamily.BAMBOO.block(WoodKind.LOG));
        assertEquals("minecraft:bamboo_block", StageWoodFamily.BAMBOO.block(WoodKind.WOOD));
        assertEquals("minecraft:bamboo_mosaic", StageWoodFamily.BAMBOO_MOSAIC.block(WoodKind.PLANKS));
        assertEquals("minecraft:bamboo_mosaic_stairs", StageWoodFamily.BAMBOO_MOSAIC.block(WoodKind.STAIRS));
        assertEquals("minecraft:stripped_bamboo_block", StageWoodFamily.BAMBOO_MOSAIC.block(WoodKind.LOG));
        assertEquals("minecraft:bamboo_door", StageWoodFamily.BAMBOO_MOSAIC.block(WoodKind.DOOR));
    }

    @Test
    @DisplayName("owning() finds the family of any member block; mosaic-only ids land on the mosaic family")
    void owning() {
        assertEquals(Optional.of(StageWoodFamily.JUNGLE), StageWoodFamily.owning("minecraft:jungle_slab"));
        assertEquals(Optional.of(StageWoodFamily.CRIMSON), StageWoodFamily.owning("minecraft:crimson_hyphae"));
        assertEquals(Optional.of(StageWoodFamily.BAMBOO), StageWoodFamily.owning("minecraft:bamboo_planks"));
        assertEquals(Optional.of(StageWoodFamily.BAMBOO_MOSAIC), StageWoodFamily.owning("minecraft:bamboo_mosaic_slab"));
        assertTrue(StageWoodFamily.owning("minecraft:stone").isEmpty());
        assertTrue(StageWoodFamily.owning(null).isEmpty());
        assertEquals(Optional.of(StageWoodFamily.DARK_OAK), StageWoodFamily.byId("Dark_Oak"));
    }
}
