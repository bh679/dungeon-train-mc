package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FallbackBookPoolTest {

    @BeforeEach
    void reset() {
        FallbackBookPool.clear();
    }

    private static String body(int... ids) {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"total\":100,\"books\":[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(ids[i])
              .append(",\"title\":\"t").append(ids[i])
              .append("\",\"author\":\"a\",\"lang\":\"en_us\",\"pages\":[\"p\"]}");
        }
        return sb.append("]}").toString();
    }

    private static List<Integer> ids() {
        return FallbackBookPool.snapshot("en").stream().map(SharedBookPool.PoolBook::id).toList();
    }

    @Test
    @DisplayName("fetches merge by id, new ids appended")
    void merges() {
        assertEquals(2, FallbackBookPool.applyResponse("en", body(1, 2)));
        assertEquals(1, FallbackBookPool.applyResponse("en", body(2, 3)));
        assertEquals(List.of(1, 2, 3), ids());
    }

    @Test
    @DisplayName("malformed or not-ok reply leaves the window untouched")
    void badReplyKeepsWindow() {
        FallbackBookPool.applyResponse("en", body(1));
        assertEquals(0, FallbackBookPool.applyResponse("en", "{\"ok\":false}"));
        assertEquals(0, FallbackBookPool.applyResponse("en", "[]"));
        assertEquals(List.of(1), ids());
    }

    @Test
    @DisplayName("window capped, oldest evicted")
    void capped() {
        int max = FallbackBookPool.MAX_SNAPSHOT;
        FallbackBookPool.applyResponse("en", body(IntStream.rangeClosed(1, max).toArray()));
        FallbackBookPool.applyResponse("en", body(max + 1, max + 2));
        List<Integer> got = ids();
        assertEquals(max, got.size());
        assertEquals(3, got.get(0));
        assertEquals(max + 2, got.get(got.size() - 1));
    }

    @Test
    @DisplayName("each family keeps its own window")
    void perFamily() {
        FallbackBookPool.applyResponse("en", body(1, 2));
        FallbackBookPool.applyResponse("de", body(3));
        assertEquals(List.of(1, 2), ids());
        assertEquals(1, FallbackBookPool.snapshot("de").size());
        assertEquals(0, FallbackBookPool.snapshot("fr").size());
        assertEquals(0, FallbackBookPool.snapshot(null).size());
    }
}
