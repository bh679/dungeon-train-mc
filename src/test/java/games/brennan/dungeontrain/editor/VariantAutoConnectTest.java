package games.brennan.dungeontrain.editor;

import com.google.gson.JsonParser;
import games.brennan.dungeontrain.item.VariantClipboardItem;
import games.brennan.dungeontrain.net.BlockVariantSyncPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-row fence / wall / pane {@link VariantState#autoConnect()} flag: which blocks carry it,
 * that every copy path keeps it, and that it round-trips through the sidecar JSON, the clipboard
 * NBT and the Z-menu sync packet.
 */
final class VariantAutoConnectTest {

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
    @DisplayName("defaults off; every withX keeps the flag; it breaks the bare-string form")
    void flagCarriesThrough() {
        VariantState plain = VariantState.of(fenceNorth());
        assertFalse(plain.autoConnect(), "default must be off");
        assertTrue(plain.isPlainBareString());

        VariantState on = plain.withAutoConnect(true);
        assertTrue(on.autoConnect());
        assertFalse(on.isPlainBareString());
        assertTrue(on.withWeight(4).autoConnect());
        assertTrue(on.withRotation(VariantRotation.NONE).autoConnect());
        assertTrue(on.withHalf(VariantHalf.NONE).autoConnect());
        assertTrue(on.withActive(VariantActive.NONE).autoConnect());
        assertTrue(on.withGroupRef(0).autoConnect());
        assertTrue(on.withLinkedLootPrefabId(null).autoConnect());
        assertTrue(on.withDifficulty(VariantDifficulty.NONE).autoConnect());
        assertTrue(on.withState(Blocks.COBBLESTONE_WALL.defaultBlockState(), null).autoConnect());
        assertFalse(on.withAutoConnect(false).autoConnect());
    }

    @Test
    @DisplayName("JSON: on emits \"autoConnect\": true and keeps the stored arms; off stays a bare string")
    void jsonRoundTrip() {
        VariantState on = VariantState.of(fenceNorth()).withAutoConnect(true);
        String json = write(on);
        assertTrue(json.contains("\"autoConnect\": true"), json);
        VariantState back = parse(json);
        assertTrue(back.autoConnect());
        assertTrue(back.state().getValue(BlockStateProperties.NORTH), "stored arms survive: " + json);

        String off = write(VariantState.of(fenceNorth()));
        assertFalse(off.contains("autoConnect"), off);
        assertTrue(off.startsWith("\"minecraft:oak_fence["), "default must stay a bare string: " + off);
        assertFalse(parse(off).autoConnect());

        // A pre-flag object entry reads as off.
        assertFalse(parse("{\"state\": \"minecraft:oak_fence\", \"weight\": 2}").autoConnect());
    }

    @Test
    @DisplayName("clipboard NBT round-trips the flag")
    void clipboardRoundTrip() {
        List<VariantState> states = List.of(
            VariantState.of(fenceNorth()).withAutoConnect(true),
            VariantState.of(Blocks.COBBLESTONE_WALL.defaultBlockState()));
        List<VariantState> back = VariantClipboardItem.decodeStates(VariantClipboardItem.encodeStates(states, 0));
        assertEquals(2, back.size());
        assertTrue(back.get(0).autoConnect());
        assertFalse(back.get(1).autoConnect());
    }

    @Test
    @DisplayName("sync packet carries the per-row flag and the plot's support bit")
    void syncPacketRoundTrip() {
        BlockVariantSyncPacket.Entry on = new BlockVariantSyncPacket.Entry(
            "minecraft:oak_fence", null, 1, (byte) 0, (byte) 0, null, null, (byte) 1, 0, -1,
            0, false, BlockVariantSyncPacket.Entry.ACTIVE_MODE_DEFAULT, true);
        BlockVariantSyncPacket.Entry off = new BlockVariantSyncPacket.Entry(
            "minecraft:oak_fence", null, 1, (byte) 0, (byte) 0);
        BlockVariantSyncPacket packet = new BlockVariantSyncPacket("carriage:test", CELL, List.of(on, off), 0,
            Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, (byte) 0, false, (byte) 0, (byte) 0, true);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        packet.encode(buf);
        BlockVariantSyncPacket back = BlockVariantSyncPacket.decode(buf);
        assertTrue(back.autoConnectSupported());
        assertTrue(back.entries().get(0).autoConnect());
        assertFalse(back.entries().get(1).autoConnect());
    }
}
