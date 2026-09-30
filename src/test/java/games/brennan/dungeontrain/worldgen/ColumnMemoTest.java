package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The column memo behind the End-islands density memo: keyed by (x, z) only, and bounded. */
class ColumnMemoTest {

    @Test
    @DisplayName("a column remembers its value and negative coordinates don't collide")
    void remembersByColumn() {
        ColumnMemo memo = new ColumnMemo(8);
        assertNull(memo.get(5, 7));
        memo.put(5, 7, 0.25);
        assertEquals(0.25, memo.get(5, 7));
        assertNull(memo.get(7, 5), "x and z are not interchangeable");
        memo.put(-5, 7, -0.5);
        memo.put(5, -7, 0.75);
        assertEquals(-0.5, memo.get(-5, 7));
        assertEquals(0.75, memo.get(5, -7));
        assertEquals(0.25, memo.get(5, 7));
        assertEquals(3, memo.size());
    }

    @Test
    @DisplayName("past its cap the memo starts over rather than growing")
    void bounded() {
        ColumnMemo memo = new ColumnMemo(2);
        memo.put(1, 0, 1.0);
        memo.put(2, 0, 2.0);
        assertEquals(2, memo.size());
        memo.put(3, 0, 3.0);
        assertEquals(1, memo.size(), "the third column drops the first two");
        assertNull(memo.get(1, 0));
        assertEquals(3.0, memo.get(3, 0));
    }
}
