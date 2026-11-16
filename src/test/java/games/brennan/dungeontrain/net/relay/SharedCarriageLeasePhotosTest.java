package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code /carriages/lease} reply's {@code photos}: the community photos a drifting build holds,
 * which a leasing world needs to find their pictures. A relay too old to list them reads as none.
 */
final class SharedCarriageLeasePhotosTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static final String BASE =
            "\"ok\":true,\"id\":7,\"token\":\"tok\",\"blocks\":\"B\",\"dims\":{\"l\":9,\"h\":5,\"w\":5}";

    @Test
    @DisplayName("a lease lists its photos with their key, author and counts")
    void photosKept() {
        SharedCarriageClient.PoolLease lease = SharedCarriageClient.parseLease(json("{" + BASE
                + ",\"photos\":[{\"id\":12,\"author\":\"Alex\",\"viewsLeft\":0,\"views\":3,\"maxViews\":3,\"tributes\":1},"
                + "{\"id\":13,\"key\":\"k13\",\"author\":\"Steve\"}]}"), 1, 1, 1);
        assertNotNull(lease);
        List<SharedCarriageClient.LeasePhoto> photos = lease.photos();
        assertEquals(2, photos.size());
        assertEquals(new SharedCarriageClient.LeasePhoto(12, "", "Alex", 0, 3, 3, 1), photos.get(0));
        assertEquals(new SharedCarriageClient.LeasePhoto(13, "k13", "Steve", -1, -1, -1, -1), photos.get(1));
    }

    @Test
    @DisplayName("no photos field, a non-array, or entries without an id read as none")
    void photosMissing() {
        assertTrue(SharedCarriageClient.parseLease(json("{" + BASE + "}"), 1, 1, 1).photos().isEmpty());
        assertTrue(SharedCarriageClient.parseLease(json("{" + BASE + ",\"photos\":\"x\"}"), 1, 1, 1).photos().isEmpty());
        assertTrue(SharedCarriageClient.parseLease(
                json("{" + BASE + ",\"photos\":[{\"author\":\"A\"},{\"id\":0},7]}"), 1, 1, 1).photos().isEmpty());
    }

    @Test
    @DisplayName("an author name from the wire is cleaned of formatting codes")
    void authorSanitised() {
        SharedCarriageClient.PoolLease lease = SharedCarriageClient.parseLease(
                json("{" + BASE + ",\"photos\":[{\"id\":4,\"author\":\"\\u00a7cBad\"}]}"), 1, 1, 1);
        assertEquals("cBad", lease.photos().get(0).author());
    }
}
