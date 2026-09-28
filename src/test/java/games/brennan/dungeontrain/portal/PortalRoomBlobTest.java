package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.PoolLease;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A room's blocks as one snapshot, from a lease or a live capture, riding on the structure. */
class PortalRoomBlobTest {

    private static CompoundTag snapshot(int l, int h, int w) {
        CompoundTag root = new CompoundTag();
        root.putInt("v", 2);
        root.putInt("l", l);
        root.putInt("h", h);
        root.putInt("w", w);
        root.put("cells", new ListTag());
        root.put("ents", new ListTag());
        return root;
    }

    private static PoolLease lease(CompoundTag snap, String owner) throws Exception {
        return new PoolLease(12, "tok", CarriageBlockSnapshot.encode(snap), snap.getInt("l"), snap.getInt("h"),
                snap.getInt("w"), 4, List.of(), owner,
                new SharedCarriageClient.Credits("Alice", List.of("Bob"), 1),
                new SharedCarriageClient.Deaths(List.of("Carol"), 2), PoolLease.KIND_PORTAL_ROOM, "abandonedroom");
    }

    @Test
    @DisplayName("fromLease carries the relay identity, credits, deaths and seq floor; live carries none")
    void leaseVersusLive() throws Exception {
        PortalRoomBlob leased = PortalRoomBlob.fromLease(lease(snapshot(16, 10, 12), "abc"), true);
        assertTrue(leased.isLeased());
        assertEquals(12, leased.relayId());
        assertEquals("tok", leased.token());
        assertEquals("abc", leased.owner());
        assertEquals("Alice", leased.credits().creator());
        assertEquals(2, leased.deaths().total());
        assertEquals(4, leased.seqSeed());
        assertTrue(leased.authoredHere());
        assertTrue(leased.matches(16, 10, 12));
        assertFalse(leased.matches(16, 10, 13));

        PortalRoomBlob live = PortalRoomBlob.live(snapshot(16, 10, 12));
        assertFalse(live.isLeased());
        assertNull(live.relayId());
        assertEquals("", live.owner());
        assertSame(SharedCarriageClient.Credits.EMPTY, live.credits());
        assertEquals(0, live.seqSeed());
    }

    @Test
    @DisplayName("An undecodable lease blob is refused rather than stamped as an empty room")
    void badBlobThrows() {
        PoolLease broken = new PoolLease(1, "t", "not base64 gzip", 4, 3, 3, 0, List.of(), "",
                SharedCarriageClient.Credits.EMPTY, SharedCarriageClient.Deaths.EMPTY);
        assertThrows(Exception.class, () -> PortalRoomBlob.fromLease(broken, false));
    }

    @Test
    @DisplayName("Every structure rebuild carries the blob, except a shadow — which is a frame, not a room")
    void structureCarriesTheBlob() {
        PortalRoomBlob blob = PortalRoomBlob.live(snapshot(11, 7, 9));
        PortalStructure s = new PortalStructure(new BlockPos(200, -60, -30), "default",
                new Vec3i(11, 7, 9)).withBlob(blob);
        assertTrue(s.stampsFromBlob());
        assertSame(blob, s.movedTo(new BlockPos(300, -60, -30)).blob());
        assertSame(blob, s.withTiling(PortalRoomTiling.base()).blob());
        assertSame(blob, s.withExitCopies(PortalExitCopies.NONE).blob());
        assertSame(blob, s.withExitTile(PortalRoomTiling.Tile.BASE).blob());
        assertNull(s.withBlob(null).blob());
        assertFalse(s.withBlob(null).stampsFromBlob());
        assertNull(s.shadowAt(new PortalRoomTiling.Tile(1, 0)).blob(), "a shadow never stamps a blob at a tile");
        assertNotNull(s.exitShadow());
    }
}
