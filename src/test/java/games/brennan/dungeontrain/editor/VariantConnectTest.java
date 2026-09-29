package games.brennan.dungeontrain.editor;

import com.google.gson.JsonParser;
import games.brennan.dungeontrain.editor.VariantConnect.Mode;
import games.brennan.dungeontrain.item.VariantClipboardItem;
import games.brennan.dungeontrain.net.BlockVariantSyncPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-row fence / wall / pane connect mode ({@link VariantConnect.Mode}): which blocks carry
 * it, what On / Off force, that every copy path keeps it, and that all four modes round-trip
 * through the sidecar JSON, the clipboard NBT and the Z-menu sync packet.
 */
final class VariantConnectTest {

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

    private static BlockState fenceNorth() {
        return Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.NORTH, true);
    }

    @Test
    @DisplayName("fences, panes, bars and walls can connect; gates, trapdoors and cubes can't")
    void canConnect() {
        assertTrue(VariantConnect.canConnect(Blocks.OAK_FENCE.defaultBlockState()));
        assertTrue(VariantConnect.canConnect(Blocks.NETHER_BRICK_FENCE.defaultBlockState()));
        assertTrue(VariantConnect.canConnect(Blocks.GLASS_PANE.defaultBlockState()));
        assertTrue(VariantConnect.canConnect(Blocks.IRON_BARS.defaultBlockState()));
        assertTrue(VariantConnect.canConnect(Blocks.COBBLESTONE_WALL.defaultBlockState()));
        assertFalse(VariantConnect.canConnect(Blocks.OAK_FENCE_GATE.defaultBlockState()));
        assertFalse(VariantConnect.canConnect(Blocks.OAK_TRAPDOOR.defaultBlockState()));
        assertFalse(VariantConnect.canConnect(Blocks.STONE.defaultBlockState()));
        assertFalse(VariantConnect.canConnect(null));
    }

    @Test
    @DisplayName("On puts every arm out, Off pulls every arm in — fences, panes and walls")
    void forceArms() {
        BlockState fenceOn = VariantConnect.force(fenceNorth(), true);
        BlockState fenceOff = VariantConnect.force(fenceNorth(), false);
        BlockState paneOn = VariantConnect.force(Blocks.GLASS_PANE.defaultBlockState(), true);
        for (var arm : List.of(BlockStateProperties.NORTH, BlockStateProperties.EAST,
                BlockStateProperties.SOUTH, BlockStateProperties.WEST)) {
            assertTrue(fenceOn.getValue(arm), "fence On " + arm.getName());
            assertFalse(fenceOff.getValue(arm), "fence Off " + arm.getName());
            assertTrue(paneOn.getValue(arm), "pane On " + arm.getName());
        }

        BlockState wall = Blocks.COBBLESTONE_WALL.defaultBlockState().setValue(WallBlock.EAST_WALL, WallSide.TALL);
        BlockState wallOn = VariantConnect.force(wall, true);
        BlockState wallOff = VariantConnect.force(wall, false);
        for (var arm : List.of(WallBlock.NORTH_WALL, WallBlock.EAST_WALL, WallBlock.SOUTH_WALL, WallBlock.WEST_WALL)) {
            assertEquals(WallSide.LOW, wallOn.getValue(arm), "wall On " + arm.getName());
            assertEquals(WallSide.NONE, wallOff.getValue(arm), "wall Off " + arm.getName());
        }
        assertTrue(wallOn.getValue(WallBlock.UP));
        assertTrue(wallOff.getValue(WallBlock.UP), "a lone wall keeps its post");

        BlockState stone = Blocks.STONE.defaultBlockState();
        assertSame(stone, VariantConnect.force(stone, true));
        // Default, On and Off never read the level, so a null one is fine here.
        assertEquals(fenceNorth(), VariantConnect.resolve(fenceNorth(), Mode.DEFAULT, null, CELL));
        assertEquals(fenceOn, VariantConnect.resolve(fenceNorth(), Mode.ON, null, CELL));
        assertEquals(fenceOff, VariantConnect.resolve(fenceNorth(), Mode.OFF, null, CELL));
    }

    @Test
    @DisplayName("defaults to Default; every withX keeps the mode; non-default breaks the bare-string form")
    void modeCarriesThrough() {
        VariantState plain = VariantState.of(fenceNorth());
        assertEquals(Mode.DEFAULT, plain.connect());
        assertTrue(plain.isPlainBareString());

        VariantState on = plain.withConnect(Mode.ON);
        assertEquals(Mode.ON, on.connect());
        assertFalse(on.isPlainBareString());
        assertEquals(Mode.ON, on.withWeight(4).connect());
        assertEquals(Mode.ON, on.withRotation(VariantRotation.NONE).connect());
        assertEquals(Mode.ON, on.withHalf(VariantHalf.NONE).connect());
        assertEquals(Mode.ON, on.withActive(VariantActive.NONE).connect());
        assertEquals(Mode.ON, on.withGroupRef(0).connect());
        assertEquals(Mode.ON, on.withLinkedLootPrefabId(null).connect());
        assertEquals(Mode.ON, on.withDifficulty(VariantDifficulty.NONE).connect());
        assertEquals(Mode.ON, on.withState(Blocks.COBBLESTONE_WALL.defaultBlockState(), null).connect());
        assertEquals(Mode.DEFAULT, on.withConnect(null).connect(), "null normalises to Default");
    }

    @Test
    @DisplayName("JSON: non-default modes emit \"connect\" and round-trip; Default stays a bare string")
    void jsonRoundTrip() {
        for (Mode mode : List.of(Mode.AUTO, Mode.ON, Mode.OFF)) {
            String json = write(VariantState.of(fenceNorth()).withConnect(mode));
            assertTrue(json.contains("\"connect\": \"" + mode.id() + "\""), json);
            VariantState back = parse(json);
            assertEquals(mode, back.connect());
            assertTrue(back.state().getValue(BlockStateProperties.NORTH), "stored arms survive: " + json);
        }

        String plain = write(VariantState.of(fenceNorth()));
        assertFalse(plain.contains("connect"), plain);
        assertTrue(plain.startsWith("\"minecraft:oak_fence["), "Default must stay a bare string: " + plain);
        assertEquals(Mode.DEFAULT, parse(plain).connect());

        // A pre-flag object entry, and an unknown token, read as Default.
        assertEquals(Mode.DEFAULT, parse("{\"state\": \"minecraft:oak_fence\", \"weight\": 2}").connect());
        assertEquals(Mode.DEFAULT, parse("{\"state\": \"minecraft:oak_fence\", \"connect\": \"sideways\"}").connect());
    }

    @Test
    @DisplayName("clipboard NBT round-trips every mode")
    void clipboardRoundTrip() {
        List<VariantState> states = new ArrayList<>();
        for (Mode mode : Mode.values()) states.add(VariantState.of(fenceNorth()).withConnect(mode));
        List<VariantState> back = VariantClipboardItem.decodeStates(VariantClipboardItem.encodeStates(states, 0));
        assertEquals(Mode.values().length, back.size());
        for (int i = 0; i < back.size(); i++) {
            assertEquals(Mode.values()[i], back.get(i).connect());
        }
    }

    @Test
    @DisplayName("sync packet carries each row's mode and the plot's support bit")
    void syncPacketRoundTrip() {
        List<BlockVariantSyncPacket.Entry> entries = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            entries.add(new BlockVariantSyncPacket.Entry(
                "minecraft:oak_fence", null, 1, (byte) 0, (byte) 0, null, null, (byte) 1, 0, -1,
                0, false, BlockVariantSyncPacket.Entry.ACTIVE_MODE_DEFAULT, (byte) mode.ordinal()));
        }
        entries.add(new BlockVariantSyncPacket.Entry("minecraft:oak_fence", null, 1, (byte) 0, (byte) 0));
        BlockVariantSyncPacket packet = new BlockVariantSyncPacket("carriage:test", CELL, entries, 0,
            Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, (byte) 0, false, (byte) 0, (byte) 0, true);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        packet.encode(buf);
        BlockVariantSyncPacket back = BlockVariantSyncPacket.decode(buf);
        assertTrue(back.connectSupported());
        for (Mode mode : Mode.values()) {
            assertEquals(mode, Mode.fromOrdinal(back.entries().get(mode.ordinal()).connectMode()));
        }
        assertEquals(Mode.DEFAULT, Mode.fromOrdinal(back.entries().get(Mode.values().length).connectMode()),
            "compat constructor defaults to Default");
    }
}
