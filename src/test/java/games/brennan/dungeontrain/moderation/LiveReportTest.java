package games.brennan.dungeontrain.moderation;

import games.brennan.dungeontrain.moderation.LiveReport.Stream;
import games.brennan.dungeontrain.moderation.LiveReport.Target.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** /livereport: which stream a player is reporting, the reasons sent, and "from 5 min ago". */
final class LiveReportTest {

    private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID AL = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Stream ONE = new Stream(1, "main", "Alice", AL);
    private static final Stream TWO = new Stream(2, "second", "Bo", null);

    @Test
    void nothingLiveIsNone() {
        assertEquals(Kind.NONE, LiveReport.resolve(List.of(), ME, "Me").kind());
    }

    @Test
    void oneStreamIsTheTarget() {
        LiveReport.Target t = LiveReport.resolve(List.of(ONE), ME, "Me");
        assertEquals(Kind.ONE, t.kind());
        assertEquals(ONE, t.only());
    }

    @Test
    void twoStreamsAsksWhich() {
        LiveReport.Target t = LiveReport.resolve(List.of(ONE, TWO), ME, "Me");
        assertEquals(Kind.PICK, t.kind());
        assertEquals(List.of(ONE, TWO), t.streams());
    }

    @Test
    void ownStreamIsNeverATarget() {
        // By uuid where the stream has one...
        assertEquals(Kind.SELF_ONLY, LiveReport.resolve(List.of(ONE), AL, "Someone").kind());
        // ...by name, any case, where it does not.
        assertEquals(Kind.SELF_ONLY, LiveReport.resolve(List.of(TWO), ME, "bo").kind());
        // Streaming alongside another: the other one, no question asked.
        LiveReport.Target t = LiveReport.resolve(List.of(ONE, TWO), ME, "Bo");
        assertEquals(Kind.ONE, t.kind());
        assertEquals(ONE, t.only());
    }

    @Test
    void uuidBeatsAMatchingName() {
        // A different player who happens to share the name is still reportable when the uuid is known.
        assertEquals(Kind.ONE, LiveReport.resolve(List.of(ONE), ME, "alice").kind());
    }

    @Test
    void byIndexSkipsOwnAndMissing() {
        assertEquals(TWO, LiveReport.byIndex(List.of(ONE, TWO), 2, ME, "Me"));
        assertNull(LiveReport.byIndex(List.of(ONE), 2, ME, "Me"));
        assertNull(LiveReport.byIndex(List.of(ONE, TWO), 2, ME, "BO"));
    }

    @Test
    void presetReasonsAreCodesTypedTextIsKept() {
        assertEquals("hate_speech", LiveReport.reason(" Hate_Speech "));
        assertEquals("other", LiveReport.reason("other"));
        assertEquals("said something awful", LiveReport.reason("  said something awful "));
        assertEquals("", LiveReport.reason(null));
        assertEquals(LiveReport.MAX_REASON, LiveReport.reason("x".repeat(500)).length());
    }

    @Test
    void ago() {
        long now = 10_000_000_000L;
        assertEquals(new LiveReport.Ago("just_now", 0), LiveReport.ago(now - 30_000, now));
        assertEquals(new LiveReport.Ago("minutes", 5), LiveReport.ago(now - 5 * 60_000 - 1, now));
        assertEquals(new LiveReport.Ago("hours", 2), LiveReport.ago(now - 2 * 3_600_000L, now));
        assertEquals(new LiveReport.Ago("days", 3), LiveReport.ago(now - 3 * 86_400_000L, now));
        // Unknown or in the future: just now, never a negative count.
        assertEquals(new LiveReport.Ago("just_now", 0), LiveReport.ago(0, now));
        assertEquals(new LiveReport.Ago("just_now", 0), LiveReport.ago(now + 60_000, now));
    }
}
