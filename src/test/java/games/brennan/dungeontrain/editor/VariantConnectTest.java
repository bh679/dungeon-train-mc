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
 * it, the Lock arm mask, that every copy path keeps it, and that all three modes round-trip
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
    @DisplayName("force / armMask round-trip every one of the 16 arm masks on a fence, a pane and a wall")
    void everyMaskRoundTrips() {
        List<BlockState> blocks = List.of(fenceNorth(), Blocks.GLASS_PANE.defaultBlockState(),
            Blocks.COBBLESTONE_WALL.defaultBlockState().setValue(WallBlock.EAST_WALL, WallSide.TALL));
        for (BlockState base : blocks) {
            for (int mask = 0; mask <= VariantConnect.ALL; mask++) {
                BlockState forced = VariantConnect.force(base, mask);
                assertEquals(mask, VariantConnect.armMask(forced), base.getBlock() + " mask " + mask);
            }
        }
        assertEquals(VariantConnect.NORTH, VariantConnect.armMask(fenceNorth()));
        assertEquals(0, VariantConnect.armMask(Blocks.STONE.defaultBlockState()));
    }

    @Test
    @DisplayName("walls: a new arm is LOW, an existing TALL arm keeps its height, the post stays up")
    void wallArms() {
        BlockState wall = Blocks.COBBLESTONE_WALL.defaultBlockState().setValue(WallBlock.EAST_WALL, WallSide.TALL);
        BlockState ne = VariantConnect.force(wall, VariantConnect.NORTH | VariantConnect.EAST);
        assertEquals(WallSide.LOW, ne.getValue(WallBlock.NORTH_WALL));
        assertEquals(WallSide.TALL, ne.getValue(WallBlock.EAST_WALL));
        assertEquals(WallSide.NONE, ne.getValue(WallBlock.SOUTH_WALL));
        assertEquals(WallSide.NONE, ne.getValue(WallBlock.WEST_WALL));
        assertTrue(VariantConnect.force(wall, 0).getValue(WallBlock.UP), "a lone wall keeps its post");

        BlockState stone = Blocks.STONE.defaultBlockState();
        assertSame(stone, VariantConnect.force(stone, VariantConnect.ALL));
        // Default and Lock never read the level, so a null one is fine here.
        assertEquals(fenceNorth(), VariantConnect.resolve(fenceNorth(), Mode.DEFAULT, null, CELL));
        assertEquals(fenceNorth(), VariantConnect.resolve(fenceNorth(), Mode.LOCK, null, CELL));
    }

    @Test
    @DisplayName("defaults to Default; every withX keeps the mode; non-default breaks the bare-string form")
    void modeCarriesThrough() {
        VariantState plain = VariantState.of(fenceNorth());
        assertEquals(Mode.DEFAULT, plain.connect());
        assertTrue(plain.isPlainBareString());

        VariantState lock = plain.withConnect(Mode.LOCK);
        assertEquals(Mode.LOCK, lock.connect());
        assertFalse(lock.isPlainBareString());
        assertEquals(Mode.LOCK, lock.withWeight(4).connect());
        assertEquals(Mode.LOCK, lock.withRotation(VariantRotation.NONE).connect());
        assertEquals(Mode.LOCK, lock.withHalf(VariantHalf.NONE).connect());
        assertEquals(Mode.LOCK, lock.withActive(VariantActive.NONE).connect());
        assertEquals(Mode.LOCK, lock.withGroupRef(0).connect());
        assertEquals(Mode.LOCK, lock.withLinkedLootPrefabId(null).connect());
        assertEquals(Mode.LOCK, lock.withDifficulty(VariantDifficulty.NONE).connect());
        // The arms toggle rewrites the state and must keep the mode.
        VariantState rearmed = lock.withState(VariantConnect.force(lock.state(), VariantConnect.EAST), null);
        assertEquals(Mode.LOCK, rearmed.connect());
        assertEquals(VariantConnect.EAST, VariantConnect.armMask(rearmed.state()));
        assertEquals(Mode.DEFAULT, lock.withConnect(null).connect(), "null normalises to Default");
    }

    @Test
    @DisplayName("JSON: Auto / Lock emit \"connect\" and round-trip with their arms; Default stays a bare string")
    void jsonRoundTrip() {
        int arms = VariantConnect.NORTH | VariantConnect.WEST;
        for (Mode mode : List.of(Mode.AUTO, Mode.LOCK)) {
            VariantState s = VariantState.of(VariantConnect.force(fenceNorth(), arms)).withConnect(mode);
            String json = write(s);
            assertTrue(json.contains("\"connect\": \"" + mode.id() + "\""), json);
            VariantState back = parse(json);
            assertEquals(mode, back.connect());
            assertEquals(arms, VariantConnect.armMask(back.state()), "locked arms survive: " + json);
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
            assertEquals(VariantConnect.NORTH, VariantConnect.armMask(back.get(i).state()));
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
