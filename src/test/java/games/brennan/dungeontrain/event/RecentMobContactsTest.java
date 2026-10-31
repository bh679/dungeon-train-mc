package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecentMobContactsTest {

    @Test
    void newestFirstNoDuplicatesCapped() {
        Deque<Integer> d = null;
        for (int id : new int[] {1, 2, 3, 2, 4, 5, 6, 7, 8, 9, 10}) d = RecentMobContacts.remember(d, id, RecentMobContacts.MAX);
        assertEquals(List.of(10, 9, 8, 7, 6, 5, 4, 2), List.copyOf(d));
    }

    @Test
    void rememberingAgainMovesToFront() {
        Deque<Integer> d = new ArrayDeque<>(List.of(3, 2, 1));
        assertEquals(List.of(1, 3, 2), List.copyOf(RecentMobContacts.remember(d, 1, 8)));
        assertEquals(List.of(3, 2, 1), List.copyOf(d), "input untouched");
    }
}
