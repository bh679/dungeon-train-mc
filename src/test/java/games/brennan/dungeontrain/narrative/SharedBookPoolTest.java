package games.brennan.dungeontrain.narrative;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.narrative.SharedBookPool.PoolBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the pure selection + parsing helpers behind the weighted community-book spawn:
 * {@link SharedBookPool#weightedPick} (a deterministic weighted lottery where a book's pick chance is
 * proportional to its selection weight = admin weight + 1) and {@link SharedBookPool#parseBook} (reads
 * the relay's {@code weight} field, defaulting to 1 for an older relay). Both are pure — no Minecraft
 * bootstrap needed (no {@link net.minecraft.world.item.ItemStack} is built).
 */
final class SharedBookPoolTest {

    private static PoolBook book(int id, int weight) {
        return new PoolBook(id, "t" + id, "a" + id, List.of("p"), weight);
    }

    @Test
    @DisplayName("weightedPick returns null for an empty pool")
    void emptyPoolIsNull() {
        assertNull(SharedBookPool.weightedPick(List.of(), 12345L));
    }

    @Test
    @DisplayName("weightedPick returns null when every weight is 0 (nothing to spawn)")
    void allZeroWeightsIsNull() {
        assertNull(SharedBookPool.weightedPick(List.of(book(1, 0), book(2, 0)), 999L));
    }

    @Test
    @DisplayName("a weight-0 book among positives is never picked, positives always are")
    void zeroWeightNeverPicked() {
        List<PoolBook> pool = List.of(book(1, 0), book(2, 1));
        for (long seed = 0; seed < 500; seed++) {
            PoolBook picked = SharedBookPool.weightedPick(pool, seed);
            assertNotNull(picked);
            assertEquals(2, picked.id(), "the weight-0 book must never be chosen");
        }
    }

    @Test
    @DisplayName("pick frequency is proportional to weight (weight 3 ≈ 3× weight 1)")
    void frequencyTracksWeight() {
        // Two books: id 1 weight 1, id 2 weight 3 → id 2 should be picked ~3× as often.
        List<PoolBook> pool = List.of(book(1, 1), book(2, 3));
        int a = 0, b = 0;
        int n = 40000;
        for (long seed = 0; seed < n; seed++) {
            PoolBook picked = SharedBookPool.weightedPick(pool, seed);
            if (picked.id() == 1) a++; else b++;
        }
        // Exact for a contiguous seed sweep modulo total(4): expect 1:3 split within a small tolerance.
        double ratio = (double) b / a;
        assertTrue(ratio > 2.7 && ratio < 3.3, "expected ~3.0 got " + ratio + " (a=" + a + " b=" + b + ")");
    }

    @Test
    @DisplayName("weightedPick is deterministic for a given seed")
    void deterministic() {
        List<PoolBook> pool = List.of(book(1, 2), book(2, 5), book(3, 1));
        for (long seed = 0; seed < 100; seed++) {
            assertSame(SharedBookPool.weightedPick(pool, seed), SharedBookPool.weightedPick(pool, seed));
        }
    }

    @Test
    @DisplayName("parseBook reads the relay weight field")
    void parsesWeight() {
        JsonObject o = JsonParser.parseString(
                "{\"id\":7,\"title\":\"T\",\"author\":\"A\",\"pages\":[\"x\"],\"weight\":4}").getAsJsonObject();
        PoolBook b = SharedBookPool.parseBook(o);
        assertNotNull(b);
        assertEquals(7, b.id());
        assertEquals(4, b.weight());
    }

    @Test
    @DisplayName("parseBook defaults weight to 1 when the field is absent (older relay → uniform)")
    void weightDefaultsToOne() {
        JsonObject o = JsonParser.parseString(
                "{\"id\":8,\"title\":\"T\",\"author\":\"A\",\"pages\":[\"x\"]}").getAsJsonObject();
        PoolBook b = SharedBookPool.parseBook(o);
        assertNotNull(b);
        assertEquals(1, b.weight());
    }

    @Test
    @DisplayName("parseBook floors a negative weight at 0")
    void negativeWeightFlooredAtZero() {
        JsonObject o = JsonParser.parseString(
                "{\"id\":9,\"title\":\"T\",\"author\":\"A\",\"pages\":[\"x\"],\"weight\":-5}").getAsJsonObject();
        PoolBook b = SharedBookPool.parseBook(o);
        assertNotNull(b);
        assertEquals(0, b.weight());
    }
}
