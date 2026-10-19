package games.brennan.dungeontrain.block.stage;

import games.brennan.dungeontrain.block.stage.StageWoodFamily.WoodKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wood-family table: the vanilla woods ported from {@code scripts/parts/clone-wood-stage.py}, plus the modded woods. */
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

    @Test
    @DisplayName("modded woods: namespaced ids, vanilla naming for BoP and pale oak, BCLib naming for the BetterX woods")
    void modded() {
        assertEquals("pale_oak", StageWoodFamily.PALE_OAK.id());
        assertEquals("minecraft:stripped_pale_oak_wood", StageWoodFamily.PALE_OAK.block(WoodKind.STRIPPED_WOOD));
        assertEquals("biomesoplenty:fir", StageWoodFamily.BOP_FIR.id());
        assertEquals("biomesoplenty:stripped_fir_log", StageWoodFamily.BOP_FIR.block(WoodKind.STRIPPED_LOG));
        assertEquals("biomesoplenty:red_maple_leaves", StageWoodFamily.BOP_MAPLE.block(WoodKind.LEAVES));
        assertEquals("betternether:rubeus_stripped_log", StageWoodFamily.BN_RUBEUS.block(WoodKind.STRIPPED_LOG));
        assertEquals("betternether:rubeus_bark", StageWoodFamily.BN_RUBEUS.block(WoodKind.WOOD));
        assertEquals("betternether:rubeus_gate", StageWoodFamily.BN_RUBEUS.block(WoodKind.FENCE_GATE));
        assertEquals("betterend:tenanea_plate", StageWoodFamily.BE_TENANEA.block(WoodKind.PRESSURE_PLATE));
        assertEquals("betternether:nether_reed_stem", StageWoodFamily.BN_NETHER_REED.block(WoodKind.STRIPPED_WOOD));
        assertEquals("betterend:umbrella_tree_membrane", StageWoodFamily.BE_UMBRELLA_TREE.block(WoodKind.LEAVES));
        // The two willows stay apart.
        assertEquals(Optional.of(StageWoodFamily.BOP_WILLOW), StageWoodFamily.byId("biomesoplenty:willow"));
        assertEquals(Optional.of(StageWoodFamily.BN_WILLOW), StageWoodFamily.byId("BetterNether:Willow"));
        assertEquals(Optional.of(StageWoodFamily.BN_RUBEUS), StageWoodFamily.owning("betternether:rubeus_planks"));
        assertEquals(Optional.of(StageWoodFamily.BOP_FIR), StageWoodFamily.owning("biomesoplenty:fir_door"));
    }

    @Test
    @DisplayName("every family answers all 14 kinds, ids are unique and fit the 32-char sync string")
    void everyFamilyIsComplete() {
        Set<String> ids = new HashSet<>();
        for (StageWoodFamily f : StageWoodFamily.values()) {
            assertTrue(ids.add(f.id()), "duplicate id " + f.id());
            assertTrue(f.id().length() <= 32, f.id());
            for (WoodKind kind : WoodKind.values()) {
                String block = f.block(kind);
                assertNotNull(block, f + " " + kind);
                // Only leaves may be filled from another namespace (a wart or mushroom block).
                if (kind != WoodKind.LEAVES) assertTrue(block.startsWith(f.namespace() + ":"), f + " " + kind + " " + block);
            }
        }
    }
}
