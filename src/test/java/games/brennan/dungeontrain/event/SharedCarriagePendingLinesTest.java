package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.SharedCarriageEnterEvents.PendingLine;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The beat ordering behind a drifting carriage's entry message. Three lines can be in flight — the
 * flavour line (sent immediately), the credit line, then the death line — and the two held ones must
 * arrive in that order, each after its own pause, never bunched.
 */
class SharedCarriagePendingLinesTest {

    private static PendingLine at(int tick) {
        return new PendingLine(Component.literal("line@" + tick), tick);
    }

    @Test
    void nothingIsSentBeforeItsBeatHasElapsed() {
        List<PendingLine> q = List.of(at(50), at(95));
        assertEquals(0, SharedCarriageEnterEvents.dueCount(q, 0));
        assertEquals(0, SharedCarriageEnterEvents.dueCount(q, 49));
    }

    @Test
    void theCreditLineGoesOutAloneWhileTheDeathLineIsStillWaiting() {
        List<PendingLine> q = List.of(at(50), at(95));
        assertEquals(1, SharedCarriageEnterEvents.dueCount(q, 50), "due exactly on its tick");
        assertEquals(1, SharedCarriageEnterEvents.dueCount(q, 94), "the death line still has a beat to go");
    }

    @Test
    void bothGoOutOnceBothAreDue() {
        List<PendingLine> q = List.of(at(50), at(95));
        assertEquals(2, SharedCarriageEnterEvents.dueCount(q, 95));
        // A player who lagged past both beats gets both, still in order, rather than losing one.
        assertEquals(2, SharedCarriageEnterEvents.dueCount(q, 1000));
    }

    @Test
    void aLineStillWaitingHoldsBackEverythingBehindIt() {
        // The queue is built in due order, so this shape shouldn't arise — but if it ever did, a later
        // line must not overtake an earlier one and describe the carriage out of sequence.
        List<PendingLine> q = List.of(at(95), at(50));
        assertEquals(0, SharedCarriageEnterEvents.dueCount(q, 60));
    }

    @Test
    void anEmptyOrAbsentQueueDrainsToNothing() {
        assertEquals(0, SharedCarriageEnterEvents.dueCount(List.of(), 100));
        assertEquals(0, SharedCarriageEnterEvents.dueCount(null, 100));
    }
}
