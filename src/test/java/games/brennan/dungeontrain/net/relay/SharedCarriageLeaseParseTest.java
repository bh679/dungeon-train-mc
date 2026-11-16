package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.train.id.BuildNameIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the {@code /carriages/lease} reply's name id: the carriage id from the world a build was first
 * made in, which every world shows. A relay with none for the build (or too old to keep one) must read
 * as empty, so the leasing world sends its own.
 */
final class SharedCarriageLeaseParseTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static final String BASE =
            "\"ok\":true,\"id\":7,\"token\":\"tok\",\"blocks\":\"B\",\"dims\":{\"l\":9,\"h\":5,\"w\":5},\"fuel\":3";

    @Test
    @DisplayName("a lease carries the relay's name id")
    void nameIdKept() {
        SharedCarriageClient.PoolLease lease = SharedCarriageClient.parseLease(
                json("{" + BASE + ",\"nameId\":\"MZXW K 0C9-3\"}"), 1, 1, 1);
        assertNotNull(lease);
        assertEquals("MZXW K 0C9-3", lease.nameId());
        assertEquals(9, lease.l());
        assertEquals(3, lease.fuel());
    }

    @Test
    @DisplayName("an empty, null or missing name id reads as empty")
    void nameIdMissing() {
        assertEquals("", SharedCarriageClient.parseLease(json("{" + BASE + ",\"nameId\":\"\"}"), 1, 1, 1).nameId());
        assertEquals("", SharedCarriageClient.parseLease(json("{" + BASE + ",\"nameId\":null}"), 1, 1, 1).nameId());
        assertEquals("", SharedCarriageClient.parseLease(json("{" + BASE + "}"), 1, 1, 1).nameId());
    }

    @Test
    @DisplayName("no lease when the pool had none or the answer is incomplete")
    void noLease() {
        assertNull(SharedCarriageClient.parseLease(null, 1, 1, 1));
        assertNull(SharedCarriageClient.parseLease(json("{\"ok\":true,\"none\":true}"), 1, 1, 1));
        assertNull(SharedCarriageClient.parseLease(json("{\"ok\":true,\"id\":7,\"blocks\":\"B\"}"), 1, 1, 1));
    }

    @Test
    @DisplayName("a leased build keeps the relay's name id over this world's")
    void leaseNameIdWins() {
        SharedCarriageClient.PoolLease lease = SharedCarriageClient.parseLease(
                json("{" + BASE + ",\"nameId\":\"ABCD 1 0C9-Q\"}"), 1, 1, 1);
        // The relay's id is used as-is: this world is never consulted (a null level would throw if it were).
        assertEquals("ABCD 1 0C9-Q", BuildNameIds.forLease(lease, null, 5));
    }

    @Test
    @DisplayName("a build with no name id falls back to this world's, or empty when that can't be read")
    void leaseWithoutNameIdFallsBack() {
        SharedCarriageClient.PoolLease lease = SharedCarriageClient.parseLease(json("{" + BASE + "}"), 1, 1, 1);
        assertEquals("", BuildNameIds.forLease(lease, null, 5));
    }
}
