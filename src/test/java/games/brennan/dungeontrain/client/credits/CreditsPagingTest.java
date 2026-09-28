package games.brennan.dungeontrain.client.credits;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The See more / Prev / Next arithmetic behind the Credits page's long lists. */
final class CreditsPagingTest {

    private static List<Integer> range(int n) {
        return IntStream.range(0, n).boxed().toList();
    }

    @Test
    @DisplayName("collapsed: the short list, See more only when the full list is longer")
    void collapsed() {
        List<Integer> all = range(23);
        CreditsPaging.View<Integer> v = CreditsPaging.START.view(all.subList(0, 5), all);
        assertEquals(range(5), v.rows());
        assertTrue(v.seeMore());
        assertFalse(v.seeLess());
        assertFalse(v.atEnd());
        assertFalse(v.prev());
        assertFalse(v.next());

        CreditsPaging.View<Integer> fits = CreditsPaging.START.view(range(3), range(3));
        assertEquals(range(3), fits.rows());
        assertFalse(fits.seeMore());
        assertTrue(fits.atEnd());
        assertEquals(1, fits.pages());
    }

    @Test
    @DisplayName("expanded: ten to a page with Prev / Next, clamped at both ends")
    void expanded() {
        List<Integer> all = range(23);
        CreditsPaging p = CreditsPaging.START.expand();
        CreditsPaging.View<Integer> first = p.view(all.subList(0, 5), all);
        assertEquals(range(10), first.rows());
        assertFalse(first.seeMore());
        assertTrue(first.seeLess());
        assertEquals(CreditsPaging.START, p.nextPage().collapse());
        assertFalse(first.prev());
        assertTrue(first.next());
        assertFalse(first.atEnd());
        assertEquals(3, first.pages());

        CreditsPaging.View<Integer> last = p.nextPage().nextPage().nextPage().view(all.subList(0, 5), all);
        assertEquals(List.of(20, 21, 22), last.rows());
        assertEquals(2, last.page());
        assertTrue(last.prev());
        assertFalse(last.next());
        assertTrue(last.atEnd());
        assertEquals(0, p.prevPage().page());
    }

    @Test
    @DisplayName("a collapsed short list longer than a page is itself paged")
    void collapsedPages() {
        CreditsPaging.View<Integer> v = CreditsPaging.START.view(range(14), range(40));
        assertEquals(range(10), v.rows());
        assertTrue(v.seeMore());
        assertTrue(v.next());
        assertEquals(2, v.pages());
    }
}
