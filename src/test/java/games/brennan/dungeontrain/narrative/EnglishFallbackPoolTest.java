package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EnglishFallbackPoolTest {

    @BeforeEach
    void reset() {
        EnglishFallbackPool.clear();
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
        return EnglishFallbackPool.snapshot().stream().map(SharedBookPool.PoolBook::id).toList();
    }

    @Test
    @DisplayName("fetches merge by id, new ids appended")
    void merges() {
        assertEquals(2, EnglishFallbackPool.applyResponse(body(1, 2)));
        assertEquals(1, EnglishFallbackPool.applyResponse(body(2, 3)));
        assertEquals(List.of(1, 2, 3), ids());
    }

    @Test
    @DisplayName("malformed or not-ok reply leaves the window untouched")
    void badReplyKeepsWindow() {
        EnglishFallbackPool.applyResponse(body(1));
        assertEquals(0, EnglishFallbackPool.applyResponse("{\"ok\":false}"));
        assertEquals(0, EnglishFallbackPool.applyResponse("[]"));
        assertEquals(List.of(1), ids());
    }

    @Test
    @DisplayName("window capped, oldest evicted")
    void capped() {
        int max = EnglishFallbackPool.MAX_SNAPSHOT;
        EnglishFallbackPool.applyResponse(body(IntStream.rangeClosed(1, max).toArray()));
        EnglishFallbackPool.applyResponse(body(max + 1, max + 2));
        List<Integer> got = ids();
        assertEquals(max, got.size());
        assertEquals(3, got.get(0));
        assertEquals(max + 2, got.get(got.size() - 1));
    }
}
