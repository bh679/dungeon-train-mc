package games.brennan.dungeontrain.net.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule for putting a relay build back together: base blob, then the deltas above the watermark,
 * in seq order.
 *
 * <p>Pinned because the relay never parses any of it — it stores opaque blobs and a number — so this
 * side is the only place the rule exists, and two paths now depend on it: a leased carriage being
 * placed on a train, and a build being loaded back into an editor.</p>
 */
final class SharedCarriagePendingDeltasTest {

    @Test
    @DisplayName("deltas already folded into the base are dropped")
    void dropsFoldedDeltas() {
        List<SharedCarriageClient.DeltaRec> pending = SharedCarriageClient.pendingDeltas(
                List.of(rec(1), rec(2), rec(3), rec(4)), 2);
        assertEquals(List.of(3, 4), pending.stream().map(SharedCarriageClient.DeltaRec::seq).toList());
    }

    @Test
    @DisplayName("the rest come back in seq order, however they arrived")
    void sortsBySeq() {
        List<SharedCarriageClient.DeltaRec> pending = SharedCarriageClient.pendingDeltas(
                List.of(rec(9), rec(3), rec(7)), 0);
        assertEquals(List.of(3, 7, 9), pending.stream().map(SharedCarriageClient.DeltaRec::seq).toList());
    }

    @Test
    @DisplayName("nothing to fold is an empty list, never null")
    void emptyIsEmpty() {
        assertTrue(SharedCarriageClient.pendingDeltas(null, 0).isEmpty());
        assertTrue(SharedCarriageClient.pendingDeltas(List.of(), 0).isEmpty());
        assertTrue(SharedCarriageClient.pendingDeltas(List.of(rec(1)), 5).isEmpty());
    }

    private static SharedCarriageClient.DeltaRec rec(int seq) {
        return new SharedCarriageClient.DeltaRec(seq, "cells-" + seq);
    }
}
