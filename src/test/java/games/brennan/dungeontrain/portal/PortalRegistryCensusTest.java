package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link PortalRegistryCensus} — the window rule and the census line's shape.
 *
 * <p>The window is what keeps the census free: it is asked once per tick, and on every tick it
 * answers no, not one {@code isHeld} lookup happens. A window that drifted or never re-armed would
 * either turn a diagnostic into a per-tick tax or silence it for a session.</p>
 *
 * <p>Plain longs, ints and UUIDs, so no Minecraft bootstrap.</p>
 */
final class PortalRegistryCensusTest {

    private static final UUID TRAIN = UUID.fromString("c56286d1-262f-4589-9b4c-e46640d5166a");

    @BeforeEach
    @AfterEach
    void reset() {
        PortalRegistryCensus.clear();
    }

    @Test
    @DisplayName("the first ask is always due — a fresh world reports without waiting a window")
    void firstAskIsDue() {
        assertTrue(PortalRegistryCensus.dueAt(0L));
    }

    @Test
    @DisplayName("asking inside the window answers no, so the walk stays free")
    void insideWindowIsNotDue() {
        assertTrue(PortalRegistryCensus.dueAt(1_000L));
        assertFalse(PortalRegistryCensus.dueAt(1_001L));
        assertFalse(PortalRegistryCensus.dueAt(1_000L + PortalRegistryCensus.PERIOD_TICKS - 1));
    }

    @Test
    @DisplayName("the window re-arms exactly at the period, and again the period after that")
    void windowReArmsOnPeriod() {
        assertTrue(PortalRegistryCensus.dueAt(1_000L));
        assertTrue(PortalRegistryCensus.dueAt(1_000L + PortalRegistryCensus.PERIOD_TICKS));
        assertFalse(PortalRegistryCensus.dueAt(1_000L + PortalRegistryCensus.PERIOD_TICKS + 1));
        assertTrue(PortalRegistryCensus.dueAt(1_000L + 2L * PortalRegistryCensus.PERIOD_TICKS));
    }

    @Test
    @DisplayName("a long gap is one census, not a backlog of them")
    void longGapIsASingleCensus() {
        assertTrue(PortalRegistryCensus.dueAt(0L));
        assertTrue(PortalRegistryCensus.dueAt(100_000L));
        assertFalse(PortalRegistryCensus.dueAt(100_001L));
    }

    @Test
    @DisplayName("a backwards clock re-anchors rather than going silent — a different world was opened")
    void backwardsClockReAnchors() {
        assertTrue(PortalRegistryCensus.dueAt(500_000L));
        assertTrue(PortalRegistryCensus.dueAt(12L));
        assertFalse(PortalRegistryCensus.dueAt(13L));
    }

    @Test
    @DisplayName("clear() re-arms, so the next world's first half-minute is not silenced")
    void clearReArms() {
        assertTrue(PortalRegistryCensus.dueAt(1_000L));
        assertFalse(PortalRegistryCensus.dueAt(1_001L));
        PortalRegistryCensus.clear();
        assertTrue(PortalRegistryCensus.dueAt(1_002L));
    }

    @Test
    @DisplayName("the line carries the counts and the anchor span")
    void formatCarriesCountsAndSpan() {
        String line = PortalRegistryCensus.format(TRAIN, 463, 16, 12, 435, -114, 1320);
        assertEquals("Registry census trainId=" + TRAIN
            + " groups=463 resident=16 held=12 gone=435 anchors=[-114,1320]", line);
    }

    @Test
    @DisplayName("an empty registry prints no span — the sentinels would read as the whole int range")
    void formatOmitsSpanWhenEmpty() {
        String line = PortalRegistryCensus.format(
            TRAIN, 0, 0, 0, 0, Integer.MAX_VALUE, Integer.MIN_VALUE);
        assertEquals("Registry census trainId=" + TRAIN
            + " groups=0 resident=0 held=0 gone=0", line);
    }

    @Test
    @DisplayName("the zero-train reading has a line of its own — silence must never be the answer")
    void zeroTrainsSaysSo() {
        // A verification run spent six minutes with no train resolvable and the census said nothing
        // at all, because the report lived only inside the per-train loop while the window went on
        // being consumed. "No line" then reads identically to a healthy quiet registry, which is
        // the exact ambiguity this class exists to remove.
        assertFalse(PortalRegistryCensus.NO_TRAINS.isBlank());
        assertTrue(PortalRegistryCensus.NO_TRAINS.startsWith("Registry census"),
            "must be greppable with the counted lines: " + PortalRegistryCensus.NO_TRAINS);
    }

    @Test
    @DisplayName("gone is reported separately from held — the whole point of the census")
    void heldAndGoneAreDistinct() {
        // The follow-up sweep can only remove `gone`; deleting a `held` group is the historic
        // duplicate-on-respawn race. A line that conflated them would not answer the question.
        String line = PortalRegistryCensus.format(TRAIN, 40, 10, 30, 0, 0, 120);
        assertTrue(line.contains("held=30"), line);
        assertTrue(line.contains("gone=0"), line);
    }
}
