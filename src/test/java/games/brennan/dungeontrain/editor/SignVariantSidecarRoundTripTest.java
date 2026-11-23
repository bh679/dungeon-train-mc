package games.brennan.dungeontrain.editor;

import com.google.gson.JsonParser;
import games.brennan.dungeontrain.item.VariantClipboardItem;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cell of sign rows through the variants sidecar JSON and the Variant Clipboard item: the text
 * (in the row's {@code nbt}) and the {@code "sign"} options come back equal, and a sign row with
 * neither writes exactly what it wrote before the feature.
 */
final class SignVariantSidecarRoundTripTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static SignVariants.Text awkward() {
        return new SignVariants.Text(
            new SignVariants.Side(List.of("Mind the \"gap\"", "back\\slash", "it's über", "🚂 choo"), DyeColor.ORANGE, true),
            new SignVariants.Side(List.of("", "", "", "end"), DyeColor.BLACK, false), true);
    }

    private static List<VariantState> cell() {
        VariantState shared = VariantState.of(Blocks.OAK_WALL_SIGN.defaultBlockState());
        shared = shared.withState(shared.state(), SignVariants.withText(null, awkward()))
            .withSign(new VariantSign(true, true));
        VariantState own = VariantState.of(Blocks.CHERRY_SIGN.defaultBlockState());
        own = own.withState(own.state(), SignVariants.withText(null,
            new SignVariants.Text(new SignVariants.Side(List.of("Dining car", "", "", ""), DyeColor.BLACK, false),
                SignVariants.Side.BLANK, false)));
        VariantState plain = VariantState.of(Blocks.SPRUCE_HANGING_SIGN.defaultBlockState());
        return List.of(shared, own, plain);
    }

    @Test
    @DisplayName("A locked sign row's option survives the sidecar")
    void lockedRoundTrip() {
        VariantState locked = VariantState.of(Blocks.OAK_WALL_SIGN.defaultBlockState())
            .withSign(new VariantSign(false, true, true));
        StringBuilder sb = new StringBuilder();
        CarriageVariantBlocks.appendCellJson(sb, List.of(locked, VariantState.of(Blocks.STONE.defaultBlockState())), 0);
        String json = sb.toString();
        assertTrue(json.contains("\"sign\": \"locked\""), json);
        CarriageVariantBlocks.ParsedCell parsed = CarriageVariantBlocks.parseCellValue(
            JsonParser.parseString(json), BuiltInRegistries.BLOCK.asLookup(), "test", BlockPos.ZERO);
        assertNotNull(parsed);
        assertEquals(locked.sign(), parsed.states().get(0).sign());
    }

    @Test
    @DisplayName("Sign text and options survive the sidecar; defaults write nothing new")
    void sidecarRoundTrip() {
        StringBuilder sb = new StringBuilder();
        CarriageVariantBlocks.appendCellJson(sb, cell(), 0);
        String json = sb.toString();
        assertTrue(json.contains("\"sign\": \"auto,shared\""), json);
        assertEquals(1, json.split("\"sign\"", -1).length - 1, "only the set row writes a sign field: " + json);

        HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        CarriageVariantBlocks.ParsedCell parsed = CarriageVariantBlocks.parseCellValue(
            JsonParser.parseString(json), blocks, "test", BlockPos.ZERO);
        assertNotNull(parsed);
        assertEquals(cell(), parsed.states());
        assertEquals(awkward(), SignVariants.text(parsed.states().get(0).blockEntityNbt()));
        assertEquals(new VariantSign(true, true), parsed.states().get(0).sign());
        assertTrue(parsed.states().get(2).isPlainBareString(), "an untouched sign is still a bare string");

        // Written again, byte for byte the same.
        StringBuilder again = new StringBuilder();
        CarriageVariantBlocks.appendCellJson(again, parsed.states(), 0);
        assertEquals(json, again.toString());
    }

    @Test
    @DisplayName("A file from before the feature (no \"sign\" field) loads with none and re-saves unchanged")
    void olderFileUnchanged() {
        String older = "[{\"state\": \"minecraft:oak_wall_sign[facing=north,waterlogged=false]\", "
            + "\"nbt\": \"{front_text:{messages:['\\\"\\\"','\\\"Hi\\\"','\\\"\\\"','\\\"\\\"']}}\"}, "
            + "\"minecraft:stone\"]";
        HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        CarriageVariantBlocks.ParsedCell parsed = CarriageVariantBlocks.parseCellValue(
            JsonParser.parseString(older), blocks, "test", BlockPos.ZERO);
        assertNotNull(parsed);
        assertTrue(parsed.states().get(0).sign().isDefault());
        assertEquals("Hi", SignVariants.text(parsed.states().get(0).blockEntityNbt()).firstLine());
        StringBuilder sb = new StringBuilder();
        CarriageVariantBlocks.appendCellJson(sb, parsed.states(), 0);
        assertFalse(sb.toString().contains("\"sign\""), sb.toString());
        assertEquals(JsonParser.parseString(older), JsonParser.parseString(sb.toString()));
    }

    @Test
    @DisplayName("Sign options and text ride the Variant Clipboard item")
    void clipboardRoundTrip() {
        List<VariantState> back = VariantClipboardItem.decodeStates(VariantClipboardItem.encodeStates(cell(), 0));
        assertEquals(cell(), back);
    }

    @Test
    @DisplayName("Mirroring a sign row keeps its sign options")
    void mirrorKeepsOptions() {
        VariantState row = cell().get(0);
        assertEquals(row.sign(), EditorMirror.reflectVariant(row, true, false, false).sign());
    }
}
