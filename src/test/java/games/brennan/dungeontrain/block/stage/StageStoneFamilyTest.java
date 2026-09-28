package games.brennan.dungeontrain.block.stage;

import games.brennan.dungeontrain.block.stage.StageStoneFamily.Shape;
import games.brennan.dungeontrain.block.stage.StageStoneFamily.StoneKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kind fallback chain + shape spelling of the stone families against a fake registry. */
final class StageStoneFamilyTest {

    private static final Set<String> REGISTRY = Set.of(
        "minecraft:cobblestone_stairs", "minecraft:cobblestone_slab", "minecraft:cobblestone_wall",
        "minecraft:stone_stairs", "minecraft:stone_slab",
        "minecraft:stone_brick_stairs", "minecraft:stone_brick_slab", "minecraft:stone_brick_wall",
        "minecraft:smooth_stone_slab",
        "minecraft:mossy_stone_brick_stairs", "minecraft:mossy_stone_brick_slab", "minecraft:mossy_stone_brick_wall",
        "minecraft:cobbled_deepslate_stairs", "minecraft:cobbled_deepslate_slab", "minecraft:cobbled_deepslate_wall",
        "minecraft:deepslate_brick_stairs", "minecraft:deepslate_brick_slab", "minecraft:deepslate_brick_wall",
        "minecraft:polished_deepslate_stairs", "minecraft:polished_deepslate_slab", "minecraft:polished_deepslate_wall",
        "minecraft:nether_brick_stairs", "minecraft:nether_brick_slab", "minecraft:nether_brick_wall",
        "minecraft:quartz_stairs", "minecraft:quartz_slab", "minecraft:smooth_quartz_stairs", "minecraft:smooth_quartz_slab",
        "minecraft:andesite_stairs", "minecraft:andesite_slab", "minecraft:andesite_wall",
        "minecraft:polished_andesite_stairs", "minecraft:polished_andesite_slab");
    private static final Predicate<String> EXISTS = REGISTRY::contains;

    @Test
    @DisplayName("every family answers every kind; missing kinds walk the fallback chain")
    void fallbackChain() {
        assertEquals("minecraft:cracked_stone_bricks", StageStoneFamily.STONE.block(StoneKind.CRACKED));
        // deepslate has no mossy → cracked
        assertEquals("minecraft:cracked_deepslate_bricks", StageStoneFamily.DEEPSLATE.block(StoneKind.MOSSY));
        // tuff has neither cracked nor mossy → cobbled
        assertEquals("minecraft:tuff", StageStoneFamily.TUFF.block(StoneKind.MOSSY));
        // prismarine has no feature → polished
        assertEquals("minecraft:dark_prismarine", StageStoneFamily.PRISMARINE.block(StoneKind.FEATURE));
        // end stone has no polished → stone
        assertEquals("minecraft:end_stone", StageStoneFamily.END_STONE.block(StoneKind.POLISHED));
        // andesite has no cobbled / bricks → stone
        assertEquals("minecraft:andesite", StageStoneFamily.ANDESITE.block(StoneKind.COBBLED));
        assertEquals("minecraft:andesite", StageStoneFamily.ANDESITE.block(StoneKind.BRICKS));
        for (StageStoneFamily f : StageStoneFamily.values()) {
            for (StoneKind k : StoneKind.values()) {
                assertTrue(f.block(k).startsWith("minecraft:"), f + "/" + k);
            }
        }
    }

    @Test
    @DisplayName("shapes spell the kind's sibling, else walk the chain, else the last resort")
    void shapes() {
        assertEquals("minecraft:cobblestone_wall", StageStoneFamily.STONE.shape(StoneKind.COBBLED, Shape.WALL, EXISTS));
        // plain stone has no wall → cobbled's
        assertEquals("minecraft:cobblestone_wall", StageStoneFamily.STONE.shape(StoneKind.STONE, Shape.WALL, EXISTS));
        // smooth stone: slab yes, stairs no → stone stairs
        assertEquals("minecraft:smooth_stone_slab", StageStoneFamily.STONE.shape(StoneKind.POLISHED, Shape.SLAB, EXISTS));
        assertEquals("minecraft:stone_stairs", StageStoneFamily.STONE.shape(StoneKind.POLISHED, Shape.STAIRS, EXISTS));
        // cracked has no shapes → cobbled's
        assertEquals("minecraft:cobblestone_stairs", StageStoneFamily.STONE.shape(StoneKind.CRACKED, Shape.STAIRS, EXISTS));
        assertEquals("minecraft:mossy_stone_brick_wall", StageStoneFamily.STONE.shape(StoneKind.MOSSY, Shape.WALL, EXISTS));
        // feature → polished (smooth stone slab) / → stone stairs
        assertEquals("minecraft:smooth_stone_slab", StageStoneFamily.STONE.shape(StoneKind.FEATURE, Shape.SLAB, EXISTS));
        // deepslate: plain deepslate aliases to cobbled deepslate
        assertEquals("minecraft:cobbled_deepslate_stairs", StageStoneFamily.DEEPSLATE.shape(StoneKind.STONE, Shape.STAIRS, EXISTS));
        // netherrack aliases to nether bricks
        assertEquals("minecraft:nether_brick_wall", StageStoneFamily.NETHER_BRICK.shape(StoneKind.COBBLED, Shape.WALL, EXISTS));
        // quartz_block → quartz_stairs via the _block rule; no quartz wall anywhere → last resort
        assertEquals("minecraft:quartz_stairs", StageStoneFamily.QUARTZ.shape(StoneKind.STONE, Shape.STAIRS, EXISTS));
        assertEquals("minecraft:cobblestone_wall", StageStoneFamily.QUARTZ.shape(StoneKind.STONE, Shape.WALL, EXISTS));
    }

    @Test
    @DisplayName("owning() matches base blocks and their shapes; declaration order breaks ties")
    void owning() {
        assertEquals(Optional.of(StageStoneFamily.STONE), StageStoneFamily.owning("minecraft:mossy_stone_brick_wall", EXISTS));
        assertEquals(Optional.of(StageStoneFamily.DEEPSLATE), StageStoneFamily.owning("minecraft:polished_deepslate_slab", EXISTS));
        assertEquals(Optional.of(StageStoneFamily.NETHER_BRICK), StageStoneFamily.owning("minecraft:netherrack", EXISTS));
        assertEquals(Optional.of(StageStoneFamily.BLACKSTONE), StageStoneFamily.owning("minecraft:polished_blackstone", EXISTS));
        assertTrue(StageStoneFamily.owning("minecraft:oak_planks", EXISTS).isEmpty());
        assertEquals(Optional.of(StageStoneFamily.RED_SANDSTONE), StageStoneFamily.byId("Red_Sandstone"));
    }
}
