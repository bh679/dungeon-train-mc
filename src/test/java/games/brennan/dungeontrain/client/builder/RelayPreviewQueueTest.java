package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.client.builder.RelayPreviewQueue.Ask;
import games.brennan.dungeontrain.client.builder.RelayPreviewQueue.Tier;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static games.brennan.dungeontrain.client.builder.RelayPreviewQueue.INDEX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The order relay build pictures are asked for in the X menu. */
class RelayPreviewQueueTest {

    /** A cache to ask about: what is held, out, backing off, and each build's version index. */
    private static final class Fake implements RelayPreviewQueue.State {
        final Set<String> held = new HashSet<>();
        final Set<String> out = new HashSet<>();
        final Set<String> backing = new HashSet<>();
        final Map<Integer, int[]> versions = new HashMap<>();

        Fake hold(int id, int seq) { held.add(id + ":" + seq); return this; }
        Fake out(int id, int seq) { out.add(id + ":" + seq); return this; }
        Fake backOff(int id, int seq) { backing.add(id + ":" + seq); return this; }
        Fake index(int id, int... seqs) { versions.put(id, seqs); return this; }

        @Override public boolean has(int id, int seq) { return held.contains(id + ":" + seq); }
        @Override public boolean inFlight(int id, int seq) { return out.contains(id + ":" + seq); }
        @Override public boolean backingOff(int id, int seq) { return backing.contains(id + ":" + seq); }
        @Override public int[] versions(int id) { return versions.get(id); }
    }

    private static List<Ask> next(List<Integer> visible, int sel, int selSeq, Fake s, int slots) {
        return RelayPreviewQueue.next(visible, sel, selSeq, s, slots, true);
    }

    private static Ask ask(int id, int seq, Tier tier) {
        return new Ask(id, seq, tier);
    }

    @Test
    void newestOfEveryVisibleBuildFirstInGridOrder() {
        Fake s = new Fake();
        assertEquals(List.of(ask(1, 0, Tier.NEWEST), ask(2, 0, Tier.NEWEST), ask(3, 0, Tier.NEWEST)),
            next(List.of(1, 2, 3), 0, 0, s, 3));
    }

    @Test
    void noHistoryWhileAnyVisibleBuildLacksItsNewestPicture() {
        Fake s = new Fake().hold(1, 0).hold(2, 0).out(3, 0).index(1, 10, 20);
        assertTrue(next(List.of(1, 2, 3), 0, 0, s, 3).isEmpty(),
            "build 3's current picture is still out, so nothing older may be asked for");
    }

    @Test
    void aBuildBackingOffStillHoldsHistoryBack() {
        Fake s = new Fake().hold(1, 0).backOff(2, 0);
        assertTrue(next(List.of(1, 2), 0, 0, s, 3).isEmpty());
    }

    @Test
    void noPictureCountsAsDone() {
        // "has" is true for a remembered "no picture" too; history then starts.
        Fake s = new Fake().hold(1, 0).hold(2, 0);
        assertEquals(List.of(ask(1, INDEX, Tier.HISTORY), ask(2, INDEX, Tier.HISTORY)),
            next(List.of(1, 2), 0, 0, s, 3));
    }

    @Test
    void historyIsRoundRobinNewestFirst() {
        // Index answers cache the newest recorded frame, so 30 and 200 are already held.
        Fake s = new Fake().hold(1, 0).hold(2, 0)
            .index(1, 10, 20, 30).hold(1, 30)
            .index(2, 100, 200).hold(2, 200);
        assertEquals(List.of(ask(1, 20, Tier.HISTORY), ask(2, 100, Tier.HISTORY), ask(1, 10, Tier.HISTORY)),
            next(List.of(1, 2), 0, 0, s, 5));
    }

    @Test
    void historyStopsWhenTheVersionBudgetIsFull() {
        Fake s = new Fake().hold(1, 0).index(1, 10, 20);
        assertTrue(RelayPreviewQueue.next(List.of(1), 0, 0, s, 3, false).isEmpty());
    }

    @Test
    void selectedBuildsHistoryGoesAheadOfOtherBuildsNewest() {
        Fake s = new Fake().hold(2, 0).index(2, 5, 6, 7);
        assertEquals(List.of(ask(2, 7, Tier.SELECTED), ask(2, 6, Tier.SELECTED), ask(2, 5, Tier.SELECTED)),
            next(List.of(1, 2, 3), 2, 0, s, 3));
    }

    @Test
    void selectedBuildAsksItsPictureThenPagedVersionThenIndex() {
        Fake s = new Fake();
        assertEquals(List.of(ask(2, 0, Tier.SELECTED), ask(2, 40, Tier.SELECTED), ask(2, INDEX, Tier.SELECTED)),
            next(List.of(1, 2), 2, 40, s, 3));
    }

    @Test
    void pagedVersionJumpsToTheFront() {
        Fake s = new Fake().hold(2, 0).index(2, 5, 6, 7).hold(2, 7);
        assertEquals(List.of(ask(2, 5, Tier.SELECTED)), next(List.of(2), 2, 5, s, 1));
    }

    @Test
    void neverMoreThanTheFreeSlots() {
        Fake s = new Fake();
        assertEquals(1, next(List.of(1, 2, 3, 4), 0, 0, s, 1).size());
        assertTrue(next(List.of(1, 2, 3, 4), 0, 0, s, 0).isEmpty());
    }

    @Test
    void nothingAskedTwice() {
        // Selected and on screen: its picture is asked once, as SELECTED.
        List<Ask> asks = next(List.of(1, 2), 1, 0, new Fake(), 5);
        assertEquals(List.of(ask(1, 0, Tier.SELECTED), ask(1, INDEX, Tier.SELECTED), ask(2, 0, Tier.NEWEST)),
            asks);
    }

    @Test
    void indexInFlightIsNotAskedAgain() {
        // The selected build's picture came back while its index is still out: one index ask only.
        Fake s = new Fake().hold(1, 0).out(1, INDEX);
        assertTrue(next(List.of(1), 1, 0, s, 3).isEmpty());
    }

    @Test
    void aBuildWithNoHistoryIsDoneAfterItsIndex() {
        // An empty index is remembered; nothing else is asked about that build.
        Fake s = new Fake().hold(1, 0).index(1);
        assertTrue(next(List.of(1), 1, 0, s, 3).isEmpty());
    }

    @Test
    void historyStartsWithEveryIndexBeforeAnyPastVersion() {
        Fake s = new Fake().hold(1, 0).hold(2, 0).index(1, 10, 20).hold(1, 20);
        assertEquals(List.of(ask(2, INDEX, Tier.HISTORY), ask(1, 10, Tier.HISTORY)),
            next(List.of(1, 2), 0, 0, s, 3));
    }
}
