package games.brennan.dungeontrain.editor;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VariantActive} on {@link VariantState}: constructor normalisation,
 * the {@code "active"} sidecar field, and the read-time migration for
 * pre-flag entries whose state already carries {@code open=true}.
 */
final class VariantActiveTest {

    private static final BlockPos CELL = new BlockPos(1, 1, 1);

    private static VariantState parse(String json) {
        VariantState out = CarriageVariantBlocks.parseVariantElement(
            JsonParser.parseString(json), BuiltInRegistries.BLOCK.asLookup(), "test", CELL);
        assertNotNull(out, "parse returned null for " + json);
        return out;
    }

    private static String write(VariantState s) {
        StringBuilder sb = new StringBuilder();
        CarriageVariantBlocks.appendVariantJson(sb, s);
        return sb.toString();
    }

    @Test
    @DisplayName("canonical ctor keeps the stored state's toggle in step with the mode")
    void ctor_syncsStateProperty() {
        BlockState closed = Blocks.OAK_TRAPDOOR.defaultBlockState();
        VariantState active = VariantState.of(closed).withActive(VariantActive.active());
        assertTrue(active.state().getValue(BlockStateProperties.OPEN), "ACTIVE stores open=true");

        VariantState backOff = active.withActive(VariantActive.NONE);
        assertFalse(backOff.state().getValue(BlockStateProperties.OPEN), "INACTIVE stores open=false");

        VariantState random = active.withActive(VariantActive.random());
        assertFalse(random.state().getValue(BlockStateProperties.OPEN), "RANDOM stores the off form; spawn rolls it");

        // Non-toggle blocks and mob entries are untouched.
        VariantState stone = VariantState.of(Blocks.STONE.defaultBlockState()).withActive(VariantActive.active());
        assertEquals(Blocks.STONE.defaultBlockState(), stone.state());
        assertTrue(active.withState(Blocks.STONE.defaultBlockState(), null).active().mode() == VariantActive.Mode.ACTIVE,
            "withState preserves the flag");
    }

    @Test
    @DisplayName("JSON: non-default modes emit \"active\"; the default stays a bare string")
    void json_roundTrip() {
        VariantState active = VariantState.of(Blocks.COPPER_BULB.defaultBlockState()).withActive(VariantActive.active());
        String json = write(active);
        assertTrue(json.contains("\"active\": \"active\""), "missing active field: " + json);
        assertTrue(json.contains("lit=true"), "state string must carry lit=true: " + json);
        VariantState back = parse(json);
        assertEquals(VariantActive.Mode.ACTIVE, back.active().mode());
        assertTrue(back.state().getValue(BlockStateProperties.LIT));

        VariantState random = active.withActive(VariantActive.random()).withWeight(3);
        String rjson = write(random);
        assertTrue(rjson.contains("\"active\": \"random\""), rjson);
        assertEquals(VariantActive.Mode.RANDOM, parse(rjson).active().mode());
        assertEquals(3, parse(rjson).weight());

        VariantState plain = VariantState.of(Blocks.OAK_TRAPDOOR.defaultBlockState());
        assertTrue(plain.isPlainBareString());
        String pjson = write(plain);
        assertTrue(pjson.startsWith("\"minecraft:oak_trapdoor["), "default must stay a v1 bare string: " + pjson);
        assertFalse(pjson.contains("active"), pjson);
        assertEquals(VariantActive.Mode.INACTIVE, parse(pjson).active().mode());
    }

    @Test
    @DisplayName("migration: a pre-flag entry with open=true in its state reads as ACTIVE")
    void migration_openStateBecomesActive() {
        String bare = "\"minecraft:oak_trapdoor[facing=north,half=bottom,open=true,powered=false,waterlogged=false]\"";
        VariantState fromBare = parse(bare);
        assertEquals(VariantActive.Mode.ACTIVE, fromBare.active().mode(), "bare string with open=true");
        assertTrue(fromBare.state().getValue(BlockStateProperties.OPEN));

        String obj = "{\"state\": \"minecraft:copper_bulb[lit=true,powered=false]\", \"weight\": 2}";
        VariantState fromObj = parse(obj);
        assertEquals(VariantActive.Mode.ACTIVE, fromObj.active().mode(), "object form with lit=true and no field");
        assertEquals(2, fromObj.weight());

        // Re-saving now writes the field explicitly, so the migration is one-way.
        assertTrue(write(fromObj).contains("\"active\": \"active\""));

        // An explicit field always wins over the state string.
        String forcedOff = "{\"state\": \"minecraft:oak_trapdoor[open=true]\", \"active\": \"inactive\"}";
        VariantState off = parse(forcedOff);
        assertEquals(VariantActive.Mode.INACTIVE, off.active().mode());
        assertFalse(off.state().getValue(BlockStateProperties.OPEN), "ctor re-syncs the state to the explicit mode");
    }

    @Test
    @DisplayName("short constructors derive the mode from the captured state (world-block capture of an open trapdoor stays open)")
    void shortCtor_derivesFromState() {
        BlockState open = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true);
        VariantState captured = new VariantState(open, null, 1, VariantRotation.NONE);
        assertEquals(VariantActive.Mode.ACTIVE, captured.active().mode());
        assertTrue(captured.state().getValue(BlockStateProperties.OPEN));
        VariantState placement = new VariantState(Blocks.OAK_TRAPDOOR.defaultBlockState(), null);
        assertEquals(VariantActive.Mode.INACTIVE, placement.active().mode());
    }

    @Test
    @DisplayName("mirror / transform reconstruction carries the flag through")
    void mirrorAndTransform_preserveActive() {
        VariantState active = VariantState.of(Blocks.OAK_TRAPDOOR.defaultBlockState()).withActive(VariantActive.random());
        VariantState mirrored = EditorMirror.reflectVariant(active, true, false, false);
        assertEquals(VariantActive.Mode.RANDOM, mirrored.active().mode());
    }

    @Test
    @DisplayName("editor preview: ACTIVE shows the on form, RANDOM alternates per tick")
    void preview_activePass() {
        BlockState lamp = Blocks.COPPER_BULB.defaultBlockState();
        assertTrue(VariantEditorPreviewTicker.applyActivePreview(lamp, VariantActive.active(), 0)
            .getValue(BlockStateProperties.LIT));
        assertFalse(VariantEditorPreviewTicker.applyActivePreview(lamp, VariantActive.NONE, 1)
            .getValue(BlockStateProperties.LIT));
        boolean t0 = VariantEditorPreviewTicker.applyActivePreview(lamp, VariantActive.random(), 0)
            .getValue(BlockStateProperties.LIT);
        boolean t1 = VariantEditorPreviewTicker.applyActivePreview(lamp, VariantActive.random(), 1)
            .getValue(BlockStateProperties.LIT);
        assertTrue(t0 != t1, "RANDOM preview must alternate between ticks");
    }
}
