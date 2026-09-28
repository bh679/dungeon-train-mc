package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.StagePalette;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pure derivation in {@link StagePaletteBaker#derive} against fake tallies and registries. */
final class StagePaletteBakerTest {

    /** The vanilla ids the fake registry "has" — enough for the derivation rules under test. */
    private static final Set<String> REGISTRY = Set.of(
        "minecraft:stone", "minecraft:stone_stairs", "minecraft:stone_slab", "minecraft:stone_button",
        "minecraft:stone_pressure_plate",
        "minecraft:stone_bricks", "minecraft:stone_brick_stairs", "minecraft:stone_brick_slab",
        "minecraft:deepslate_tiles", "minecraft:deepslate_tile_stairs", "minecraft:deepslate_tile_slab",
        "minecraft:deepslate", "minecraft:cobbled_deepslate", "minecraft:cobbled_deepslate_stairs",
        "minecraft:cobbled_deepslate_slab",
        "minecraft:quartz_block", "minecraft:quartz_stairs", "minecraft:quartz_slab",
        "minecraft:obsidian", "minecraft:glass", "minecraft:lantern",
        "minecraft:oak_planks", "minecraft:oak_stairs", "minecraft:oak_slab", "minecraft:oak_button",
        "minecraft:oak_pressure_plate", "minecraft:birch_log", "minecraft:birch_planks",
        "minecraft:birch_stairs", "minecraft:birch_slab", "minecraft:birch_button",
        "minecraft:birch_pressure_plate", "minecraft:crimson_stem",
        "minecraft:polished_blackstone", "minecraft:polished_blackstone_stairs",
        "minecraft:polished_blackstone_slab", "minecraft:polished_blackstone_button",
        "minecraft:polished_blackstone_pressure_plate", "minecraft:blackstone");

    private static final Set<String> NOT_SOLID = Set.of("minecraft:glass", "minecraft:lantern",
        "minecraft:stone_stairs", "minecraft:oak_stairs", "minecraft:oak_slab", "minecraft:oak_button",
        "minecraft:birch_pressure_plate");

    private static final Predicate<String> EXISTS = REGISTRY::contains;
    private static final Predicate<String> SOLID = id -> REGISTRY.contains(id) && !NOT_SOLID.contains(id);

    private static StagePalette derive(String... tally) {
        return StagePaletteBaker.derive(List.of(tally), SOLID, EXISTS);
    }

    @Test
    @DisplayName("solids are the tally's full cubes in order; stairs/slabs follow the first two by family spelling")
    void basic() {
        StagePalette p = derive("minecraft:stone_bricks", "minecraft:glass", "minecraft:deepslate_tiles",
            "minecraft:lantern", "minecraft:stone");
        assertEquals(List.of("minecraft:stone_bricks", "minecraft:deepslate_tiles", "minecraft:stone"), p.solid());
        assertEquals(List.of("minecraft:stone_brick_stairs", "minecraft:deepslate_tile_stairs"), p.stairs());
        assertEquals(List.of("minecraft:stone_brick_slab", "minecraft:deepslate_tile_slab"), p.slabs());
        assertEquals("minecraft:stone_button", p.button());
        assertEquals("minecraft:stone_pressure_plate", p.pressurePlate());
        assertEquals("spruce", p.wood());
        assertEquals("stone", p.stone());
    }

    @Test
    @DisplayName("stone family is the first tally block owned by a family; plain stone otherwise")
    void stoneFamily() {
        assertEquals("deepslate", derive("minecraft:glass", "minecraft:deepslate_tiles", "minecraft:stone").stone());
        assertEquals("blackstone", derive("minecraft:polished_blackstone", "minecraft:stone_bricks").stone());
        assertEquals("stone", derive("minecraft:oak_planks", "minecraft:glass").stone());
        assertEquals("stone", derive().stone());
    }

    @Test
    @DisplayName("a solid with no stairs family walks down to the next solid that has one; aliases apply")
    void walkDown() {
        StagePalette p = derive("minecraft:obsidian", "minecraft:deepslate", "minecraft:quartz_block");
        assertEquals(List.of("minecraft:cobbled_deepslate_stairs", "minecraft:cobbled_deepslate_stairs"), p.stairs());
        assertEquals(List.of("minecraft:cobbled_deepslate_slab", "minecraft:cobbled_deepslate_slab"), p.slabs());
    }

    @Test
    @DisplayName("a stage of only unfamilied solids falls back to the most-used real stairs in the tally, then stone")
    void tallyFallback() {
        assertEquals(List.of("minecraft:oak_stairs", "minecraft:oak_stairs"),
            derive("minecraft:obsidian", "minecraft:oak_stairs").stairs());
        StagePalette none = derive("minecraft:obsidian");
        assertEquals(List.of("minecraft:stone_stairs"), none.stairs());
        assertEquals(List.of("minecraft:stone_slab"), none.slabs());
    }

    @Test
    @DisplayName("empty tally → stone defaults everywhere")
    void empty() {
        StagePalette p = derive();
        assertEquals(StagePalette.DEFAULT, p);
    }

    @Test
    @DisplayName("wood is the first tally block owned by a family; button/plate prefer the tally, else the first solid's family")
    void wood() {
        StagePalette p = derive("minecraft:stone", "minecraft:crimson_stem", "minecraft:birch_planks",
            "minecraft:birch_pressure_plate");
        assertEquals("crimson", p.wood());
        assertEquals("minecraft:stone_button", p.button());
        assertEquals("minecraft:birch_pressure_plate", p.pressurePlate());

        StagePalette wooden = derive("minecraft:birch_planks", "minecraft:birch_log");
        assertEquals("birch", wooden.wood());
        assertEquals("minecraft:birch_button", wooden.button());
        assertEquals("minecraft:birch_pressure_plate", wooden.pressurePlate());
        // birch_log has no stairs and nothing further down — the list stays short and loops on read.
        assertEquals(List.of("minecraft:birch_stairs"), wooden.stairs());
        assertEquals("minecraft:birch_stairs", wooden.stairs(1));

        StagePalette nether = derive("minecraft:polished_blackstone", "minecraft:blackstone");
        assertEquals("minecraft:polished_blackstone_button", nether.button());
        assertEquals("minecraft:polished_blackstone_pressure_plate", nether.pressurePlate());
    }
}
