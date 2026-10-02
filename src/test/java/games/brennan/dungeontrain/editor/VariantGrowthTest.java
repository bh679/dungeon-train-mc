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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-row column growth ({@link VariantGrowth}): its packing and token forms, that every copy
 * path keeps it (and the connect mode alongside it), and that it round-trips through the sidecar
 * JSON, the clipboard NBT and the Z-menu sync packet.
 */
final class VariantGrowthTest {

    private static final BlockPos CELL = new BlockPos(1, 1, 1);
    private static final VariantGrowth DOWN_2_5_NOTIP = VariantGrowth.of(2, 5, VariantGrowth.Dir.DOWN, false);

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

    private static BlockState vineNorth() {
        return Blocks.VINE.defaultBlockState().setValue(BlockStateProperties.NORTH, true);
    }

    @Test
    @DisplayName("range clamps to 1..32 and max never drops below min")
    void clamps() {
        VariantGrowth g = VariantGrowth.of(0, 99, VariantGrowth.Dir.UP, true);
        assertEquals(1, g.min());
        assertEquals(VariantGrowth.MAX_LENGTH, g.max());
        VariantGrowth inverted = VariantGrowth.of(6, 3, VariantGrowth.Dir.UP, true);
        assertEquals(6, inverted.min());
        assertEquals(6, inverted.max());
        assertTrue(VariantGrowth.NONE.isDefault());
        assertFalse(g.isDefault());
    }

    @Test
    @DisplayName("toInt / fromInt round-trip every combination of on, dir, tip and range ends")
    void packRoundTrips() {
        for (boolean on : new boolean[] {false, true}) {
            for (VariantGrowth.Dir dir : VariantGrowth.Dir.values()) {
                for (boolean tip : new boolean[] {false, true}) {
                    for (int[] r : new int[][] {{1, 1}, {1, 32}, {7, 9}, {32, 32}}) {
                        VariantGrowth g = new VariantGrowth(on, r[0], r[1], dir, tip);
                        assertEquals(g, VariantGrowth.fromInt(g.toInt()), g.toString());
                    }
                }
            }
        }
        assertEquals(VariantGrowth.NONE, VariantGrowth.fromInt(VariantGrowth.NONE.toInt()));
    }

    @Test
    @DisplayName("sidecar tokens parse and print; malformed tokens are null")
    void tokens() {
        assertEquals("down 2-5 notip", DOWN_2_5_NOTIP.id());
        assertEquals(DOWN_2_5_NOTIP, VariantGrowth.parse("down 2-5 notip"));
        assertEquals(VariantGrowth.of(3, 3, VariantGrowth.Dir.UP, true), VariantGrowth.parse(" UP 3-3 "));
        assertNull(VariantGrowth.parse("sideways 2-5"));
        assertNull(VariantGrowth.parse("up 2"));
        assertNull(VariantGrowth.parse("up a-b"));
        assertNull(VariantGrowth.parse("up 2-5 leafy"));
        assertNull(VariantGrowth.parse(null));
    }

    @Test
    @DisplayName("rolled length stays in range, repeats for a seed and spreads across seeds")
    void rollLength() {
        VariantGrowth g = VariantGrowth.of(2, 6, VariantGrowth.Dir.DOWN, true);
        boolean[] seen = new boolean[7];
        for (long seed = 0; seed < 400; seed++) {
            int len = g.rollLength(seed, CELL.asLong(), 3);
            assertTrue(len >= 2 && len <= 6, "len " + len);
            assertEquals(len, g.rollLength(seed, CELL.asLong(), 3), "deterministic");
            seen[len] = true;
        }
        for (int len = 2; len <= 6; len++) assertTrue(seen[len], "never rolled " + len);
        assertEquals(4, VariantGrowth.of(4, 4, VariantGrowth.Dir.UP, true).rollLength(9, 9, 9));
    }

    @Test
    @DisplayName("defaults to off; every withX keeps growth; on breaks the bare-string form")
    void carriesThrough() {
        VariantState plain = VariantState.of(vineNorth());
        assertEquals(VariantGrowth.NONE, plain.growth());
        assertTrue(plain.isPlainBareString());

        VariantState grown = plain.withGrowth(DOWN_2_5_NOTIP);
        assertFalse(grown.isPlainBareString());
        assertEquals(DOWN_2_5_NOTIP, grown.withWeight(3).growth());
        assertEquals(DOWN_2_5_NOTIP, grown.withRotation(VariantRotation.NONE).growth());
        assertEquals(DOWN_2_5_NOTIP, grown.withHalf(VariantHalf.NONE).growth());
        assertEquals(DOWN_2_5_NOTIP, grown.withActive(VariantActive.NONE).growth());
        assertEquals(DOWN_2_5_NOTIP, grown.withConnect(VariantConnect.Mode.AUTO).growth());
        assertEquals(DOWN_2_5_NOTIP, grown.withGroupRef(0).growth());
        assertEquals(DOWN_2_5_NOTIP, grown.withLinkedLootPrefabId(null).growth());
        assertEquals(DOWN_2_5_NOTIP, grown.withDifficulty(VariantDifficulty.NONE).growth());
        assertEquals(DOWN_2_5_NOTIP, grown.withState(vineNorth(), null).growth());
        assertEquals(VariantGrowth.NONE, grown.withGrowth(null).growth(), "null normalises to off");
    }

    @Test
    @DisplayName("mirror keeps growth and connect; a vertical flip turns the column round")
    void mirrorKeepsGrowthAndConnect() {
        VariantState s = VariantState.of(Blocks.OAK_FENCE.defaultBlockState())
            .withConnect(VariantConnect.Mode.LOCK)
            .withGrowth(DOWN_2_5_NOTIP);
        VariantState flatFlip = EditorMirror.reflectVariant(s, true, false, true);
        assertEquals(VariantConnect.Mode.LOCK, flatFlip.connect(), "connect was dropped by the mirror");
        assertEquals(DOWN_2_5_NOTIP, flatFlip.growth());

        VariantState yFlip = EditorMirror.reflectVariant(s, false, true, false);
        assertEquals(VariantGrowth.Dir.UP, yFlip.growth().dir());
        assertEquals(VariantConnect.Mode.LOCK, yFlip.connect());
    }

    @Test
    @DisplayName("JSON: on emits \"growth\" and round-trips; off stays a bare string; bad tokens read as off")
    void jsonRoundTrip() {
        VariantState s = VariantState.of(vineNorth()).withGrowth(DOWN_2_5_NOTIP);
        String json = write(s);
        assertTrue(json.contains("\"growth\": \"down 2-5 notip\""), json);
        assertEquals(DOWN_2_5_NOTIP, parse(json).growth());

        String plain = write(VariantState.of(vineNorth()));
        assertFalse(plain.contains("growth"), plain);
        assertEquals(VariantGrowth.NONE, parse(plain).growth());

        assertEquals(VariantGrowth.NONE, parse("{\"state\": \"minecraft:vine\", \"weight\": 2}").growth());
        assertEquals(VariantGrowth.NONE, parse("{\"state\": \"minecraft:vine\", \"growth\": \"lots\"}").growth());
    }

    @Test
    @DisplayName("clipboard NBT round-trips growth")
    void clipboardRoundTrip() {
        List<VariantState> states = List.of(
            VariantState.of(vineNorth()).withGrowth(DOWN_2_5_NOTIP),
            VariantState.of(vineNorth()));
        List<VariantState> back = VariantClipboardItem.decodeStates(VariantClipboardItem.encodeStates(states, 0));
        assertEquals(DOWN_2_5_NOTIP, back.get(0).growth());
        assertEquals(VariantGrowth.NONE, back.get(1).growth());
    }

    @Test
    @DisplayName("sync packet carries each row's growth and the plot's support bit")
    void syncPacketRoundTrip() {
        List<BlockVariantSyncPacket.Entry> entries = new ArrayList<>();
        entries.add(new BlockVariantSyncPacket.Entry(
            "minecraft:vine[north=true]", null, 1, (byte) 0, (byte) 0, null, null, (byte) 1, 0, -1,
            0, false, BlockVariantSyncPacket.Entry.ACTIVE_MODE_DEFAULT, (byte) 0, DOWN_2_5_NOTIP.toInt()));
        entries.add(new BlockVariantSyncPacket.Entry("minecraft:vine", null, 1, (byte) 0, (byte) 0));
        BlockVariantSyncPacket packet = new BlockVariantSyncPacket("carriage:test", CELL, entries, 0,
            Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, (byte) 0, false, (byte) 0, (byte) 0, true, true);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        packet.encode(buf);
        BlockVariantSyncPacket back = BlockVariantSyncPacket.decode(buf);
        assertTrue(back.growthSupported());
        assertEquals(DOWN_2_5_NOTIP, VariantGrowth.fromInt(back.entries().get(0).growth()));
        assertEquals(VariantGrowth.NONE, VariantGrowth.fromInt(back.entries().get(1).growth()),
            "compat constructor defaults to off");
    }
}
