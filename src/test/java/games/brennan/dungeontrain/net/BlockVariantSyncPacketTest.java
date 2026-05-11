package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Wire-format round-trip for {@link BlockVariantSyncPacket}, focused on the
 * v6-schema {@code linkedLootPrefabId} field on each {@code Entry}.
 *
 * <p>The encoder writes a presence boolean followed by an optional UTF
 * string, so an entry with no link and an entry with a link must both
 * round-trip cleanly. This is the contract that lets the editor render
 * "TreasureChest" instead of "barrel" on a linked row.</p>
 */
final class BlockVariantSyncPacketTest {

    @Test
    @DisplayName("round-trip preserves null and non-null linkedLootPrefabId per entry")
    void roundTrip_mixedLinks() {
        BlockVariantSyncPacket.Entry plain = new BlockVariantSyncPacket.Entry(
            "minecraft:stone", null, 1, (byte) 1, (byte) 0, null);
        BlockVariantSyncPacket.Entry linked = new BlockVariantSyncPacket.Entry(
            "minecraft:barrel", null, 3, (byte) 1, (byte) 0, "TreasureChest");
        BlockVariantSyncPacket original = new BlockVariantSyncPacket(
            "carriage:flatbed",
            new BlockPos(4, 2, 4),
            List.of(plain, linked),
            0,
            new Vec3(0.5, 0.5, 0.5),
            new Vec3(1, 0, 0),
            new Vec3(0, 1, 0)
        );

        BlockVariantSyncPacket decoded = roundTrip(original);

        assertEquals(2, decoded.entries().size());
        BlockVariantSyncPacket.Entry plainOut = decoded.entries().get(0);
        BlockVariantSyncPacket.Entry linkedOut = decoded.entries().get(1);

        // Plain entry decodes with linkedLootPrefabId == null (the absent state).
        assertNull(plainOut.linkedLootPrefabId(),
            "plain entry should round-trip as unlinked");

        // Linked entry preserves the prefab id verbatim.
        assertNotNull(linkedOut.linkedLootPrefabId(),
            "linked entry should round-trip with its id");
        assertEquals("TreasureChest", linkedOut.linkedLootPrefabId());

        // All other fields round-trip unchanged.
        assertEquals("minecraft:stone", plainOut.stateString());
        assertEquals("minecraft:barrel", linkedOut.stateString());
        assertEquals(3, linkedOut.weight());
    }

    @Test
    @DisplayName("empty linkedLootPrefabId is normalised to null on encode")
    void roundTrip_emptyStringTreatedAsAbsent() {
        // The encoder treats null and empty the same way (presence byte
        // false), so an entry with "" comes back as null. This keeps the
        // wire format compact and the editor's hasLootPrefabLink check
        // honest.
        BlockVariantSyncPacket.Entry entry = new BlockVariantSyncPacket.Entry(
            "minecraft:chest", null, 1, (byte) 1, (byte) 0, "");
        BlockVariantSyncPacket original = new BlockVariantSyncPacket(
            "carriage:flatbed",
            new BlockPos(0, 0, 0),
            List.of(entry),
            0,
            Vec3.ZERO, Vec3.ZERO, Vec3.ZERO
        );
        BlockVariantSyncPacket decoded = roundTrip(original);
        assertNull(decoded.entries().get(0).linkedLootPrefabId());
    }

    @Test
    @DisplayName("backward-compat constructor defaults link to null")
    void backCompatConstructor_nullLink() {
        // The 5-arg constructor exists so call sites that don't carry a
        // loot link (clipboard paste, search-result add) compile unchanged.
        BlockVariantSyncPacket.Entry entry = new BlockVariantSyncPacket.Entry(
            "minecraft:stone", null, 1, (byte) 1, (byte) 0);
        assertNull(entry.linkedLootPrefabId());
    }

    private static BlockVariantSyncPacket roundTrip(BlockVariantSyncPacket original) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(buf);
        return BlockVariantSyncPacket.decode(buf);
    }
}
