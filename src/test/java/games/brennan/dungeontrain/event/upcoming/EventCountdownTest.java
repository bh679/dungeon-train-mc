package games.brennan.dungeontrain.event.upcoming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.brennan.dungeontrain.event.upcoming.EventCountdown.Due;
import games.brennan.dungeontrain.event.upcoming.EventCountdown.Stage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EventCountdownTest {

    private static final long START = 1_794_697_200L;
    private static final UpcomingEvent EVENT = new UpcomingEvent("burrito", "AshSeven", START, "https://twitch.tv/x", null);

    private static List<Stage> stagesAt(long nowSec, Set<String> sent) {
        return EventCountdown.due(List.of(EVENT), nowSec, sent).stream().map(Due::stage).toList();
    }

    @Test
    void eachStageFiresAtItsMoment() {
        assertEquals(List.of(Stage.DAY), stagesAt(START - 24 * 3600, Set.of()));
        assertEquals(List.of(Stage.HOUR), stagesAt(START - 3600, Set.of()));
        assertEquals(List.of(Stage.TEN_MIN), stagesAt(START - 600, Set.of()));
        assertEquals(List.of(Stage.NOW), stagesAt(START, Set.of()));
    }

    @Test
    void nothingBetweenStages() {
        assertTrue(stagesAt(START - 25 * 3600, Set.of()).isEmpty());
        assertTrue(stagesAt(START - 20 * 3600, Set.of()).isEmpty(), "a server started at T-20h must not say 24 hours");
        assertTrue(stagesAt(START - 30 * 60, Set.of()).isEmpty());
        assertTrue(stagesAt(START - 5 * 60, Set.of()).isEmpty());
    }

    @Test
    void stageStillSaidInsideGraceButNotAfter() {
        assertEquals(List.of(Stage.HOUR), stagesAt(START - 3600 + 4 * 60, Set.of()));
        assertTrue(stagesAt(START - 3600 + 5 * 60, Set.of()).isEmpty());
        assertEquals(List.of(Stage.NOW), stagesAt(START + 9 * 60, Set.of()));
        assertTrue(stagesAt(START + 10 * 60, Set.of()).isEmpty(), "silent once the event is well under way");
    }

    @Test
    void sentStageIsNotRepeated() {
        Set<String> sent = new HashSet<>();
        for (Due d : EventCountdown.due(List.of(EVENT), START - 600, sent)) sent.add(d.sentKey());
        assertTrue(stagesAt(START - 590, sent).isEmpty());
    }

    @Test
    void movedEventCountsDownAgain() {
        Set<String> sent = new HashSet<>();
        for (Due d : EventCountdown.due(List.of(EVENT), START - 600, sent)) sent.add(d.sentKey());
        UpcomingEvent moved = new UpcomingEvent("burrito", "AshSeven", START + 3600, null, null);
        assertEquals(List.of(Stage.HOUR),
            EventCountdown.due(List.of(moved), START, sent).stream().map(Due::stage).toList());
    }

    @Test
    void parsesTheLiveFileShape() {
        String json = """
            {"events":[{"id":"ashseven-everything-burrito","title":"AshSeven goes for Everything Burrito",
              "unix":1794697200,"durationMinutes":180,"url":"https://twitch.tv/ashseven7","joinLabel":"Join on Twitch",
              "summary":"s","description":"d <t:1794697200:F>"}]}
            """;
        List<UpcomingEvent> events = UpcomingEvent.parseAll(json);
        assertEquals(1, events.size());
        UpcomingEvent e = events.get(0);
        assertEquals("ashseven-everything-burrito", e.id());
        assertEquals(1794697200L, e.unixSec());
        assertEquals("https://twitch.tv/ashseven7", e.url());
        assertEquals("Join on Twitch", e.joinLabel());
    }

    @Test
    void skipsBrokenEntriesAndUnsafeLinks() {
        String json = """
            {"events":[{"id":"a","title":"A","unix":"soon"},{"title":"no id","unix":5},
              {"id":"b","title":" ","unix":5},"junk",
              {"id":"c","title":"C","unix":5,"url":"javascript:alert(1)"}]}
            """;
        List<UpcomingEvent> events = UpcomingEvent.parseAll(json);
        assertEquals(1, events.size());
        assertEquals("c", events.get(0).id());
        assertNull(events.get(0).url());
    }

    @Test
    void brokenFileIsNoEvents() {
        assertTrue(UpcomingEvent.parseAll("not json {").isEmpty());
        assertTrue(UpcomingEvent.parseAll("[]").isEmpty());
        assertTrue(UpcomingEvent.parseAll("{}").isEmpty());
    }

    @Test
    void debugPreviewPicksSoonestUpcoming() {
        UpcomingEvent later = new UpcomingEvent("later", "L", START + 100, null, null);
        UpcomingEvent past = new UpcomingEvent("past", "P", START - 100, null, null);
        assertEquals("burrito", EventCountdownDebug.next(List.of(later, past, EVENT), START - 10).id());
        assertEquals("sample", EventCountdownDebug.next(List.of(past), START).id());
    }
}
