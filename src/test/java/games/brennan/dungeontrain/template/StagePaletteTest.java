package games.brennan.dungeontrain.template;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.block.stage.StageWoodFamily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Slot looping, defaults and JSON round-trip for the baked {@link StagePalette}. */
final class StagePaletteTest {

    @Test
    @DisplayName("solid slots loop when the stage has fewer than 10 solid blocks")
    void loops() {
        StagePalette p = new StagePalette(List.of("a", "b", "c"), null, null, null, null, null);
        assertEquals("a", p.solid(0));
        assertEquals("c", p.solid(2));
        assertEquals("a", p.solid(3));
        assertEquals("b", p.solid(10));
    }

    @Test
    @DisplayName("blank fields fall back to the stone / spruce defaults")
    void defaults() {
        StagePalette p = new StagePalette(List.of(), List.of(" "), null, "", null, "not_a_wood");
        assertEquals("minecraft:stone", p.solid(0));
        assertEquals("minecraft:stone_stairs", p.stairs(1));
        assertEquals("minecraft:stone_slab", p.slab(0));
        assertEquals("minecraft:stone_button", p.button());
        assertEquals("minecraft:stone_pressure_plate", p.pressurePlate());
        assertEquals(StageWoodFamily.SPRUCE, p.woodFamily());
    }

    @Test
    @DisplayName("toJson/fromJson round-trip; a missing palette parses as null")
    void roundTrip() {
        StagePalette p = new StagePalette(List.of("minecraft:sandstone", "minecraft:cut_sandstone"),
            List.of("minecraft:sandstone_stairs"), List.of("minecraft:sandstone_slab"),
            "minecraft:stone_button", "minecraft:stone_pressure_plate", "birch");
        JsonObject o = p.toJson();
        assertEquals(p, StagePalette.fromJson(JsonParser.parseString(o.toString())));
        assertNull(StagePalette.fromJson(null));
        assertNull(StagePalette.fromJson(JsonParser.parseString("\"x\"")));
    }

    @Test
    @DisplayName("Stage carries the palette through toJson/fromJson and the with* copies")
    void stageCarriesPalette() {
        StagePalette p = new StagePalette(List.of("minecraft:tuff"), null, null, null, null, "oak");
        Stage s = new Stage("copper", "Copper", TemplateGate.DEFAULT).withPalette(p);
        Stage back = Stage.fromJson("copper", s.toJson());
        assertEquals(p, back.palette());
        assertEquals(p, back.withName("x").palette());
        assertEquals(p, back.withGate(TemplateGate.DEFAULT).palette());
        assertTrue(new Stage("x", "x", null).palette() == null);
    }

    @Test
    @DisplayName("overrides and family locks round-trip through JSON and survive a re-bake")
    void overridesAndLocks() {
        StagePalette base = new StagePalette(List.of("minecraft:stone"), null, null, null, null, "oak", "stone");
        StagePalette edited = base.withOverride("stage_block_1", "minecraft:mossy_cobblestone")
            .withWood(StageWoodFamily.BIRCH);
        assertEquals("minecraft:mossy_cobblestone", edited.override("stage_block_1"));
        assertTrue(edited.woodLocked());
        assertEquals("birch", edited.wood());

        StagePalette back = StagePalette.fromJson(JsonParser.parseString(edited.toJson().toString()));
        assertEquals(edited, back);
        assertTrue(!back.toJson().has(StagePalette.K_STONE_LOCKED));

        // A fresh derivation says oak + no overrides; the user state wins.
        StagePalette derived = new StagePalette(List.of("minecraft:tuff"), null, null, null, null, "oak", "tuff");
        StagePalette rebaked = edited.carryUserStateOnto(derived);
        assertEquals(List.of("minecraft:tuff"), rebaked.solid());
        assertEquals("birch", rebaked.wood());
        assertEquals("tuff", rebaked.stone());
        assertEquals(Map.of("stage_block_1", "minecraft:mossy_cobblestone"), rebaked.overrides());

        // Clearing: empty block id drops the override; null family unlocks but keeps the value.
        StagePalette cleared = rebaked.withOverride("stage_block_1", null).withWood(null);
        assertTrue(cleared.overrides().isEmpty());
        assertTrue(!cleared.woodLocked());
        assertEquals("birch", cleared.wood());
    }
}
