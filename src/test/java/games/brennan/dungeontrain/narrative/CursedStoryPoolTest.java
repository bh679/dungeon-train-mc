package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.narrative.CursedStoryPool.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down {@link CursedStoryPool}'s pure parse of the relay's {@code /deathnotes/landed} body —
 * the contract between dp-relay's {@code toAuthorPublic} shape and the story the cursed book tells.
 * Network + per-player caching are exercised in-game at Gate 2.
 */
final class CursedStoryPoolTest {

    @Test
    @DisplayName("parses the relay's landed shape, oldest first, preserving the relay's order")
    void parsesLandedNotes() {
        String body = """
            {"ok":true,"notes":[
              {"id":3,"ts":1000,"targetName":"Steve","deathCarriage":12,"usedTs":2000,
               "outcome":"echo_killed_target","outcomeTs":2500},
              {"id":7,"ts":3000,"targetName":"Alex","deathCarriage":-4,"usedTs":4000,
               "outcome":null,"outcomeTs":null}
            ]}""";
        List<Story> stories = CursedStoryPool.parseStories(body);
        assertNotNull(stories);
        assertEquals(2, stories.size());

        Story first = stories.get(0);
        assertEquals(3, first.id());
        assertEquals("Steve", first.targetName());
        assertEquals(12, first.deathCarriage());
        assertEquals(1000L, first.signedTs());
        assertEquals(2000L, first.landedTs());
        assertEquals("echo_killed_target", first.outcome());

        // A curse whose ending was never reported still tells a story — outcome reads as "".
        Story second = stories.get(1);
        assertEquals(7, second.id());
        assertEquals(-4, second.deathCarriage());   // behind the origin is a valid carriage
        assertEquals("", second.outcome());
    }

    @Test
    @DisplayName("a malformed or unsuccessful body returns null so the last snapshot is kept")
    void malformedBodiesKeepTheSnapshot() {
        assertNull(CursedStoryPool.parseStories("{\"ok\":false}"));
        assertNull(CursedStoryPool.parseStories("{\"notes\":[]}"));          // no ok flag
        assertNull(CursedStoryPool.parseStories("{\"ok\":true}"));           // no notes array
        assertNull(CursedStoryPool.parseStories("{\"ok\":true,\"notes\":{}}"));
        assertNull(CursedStoryPool.parseStories("\"not an object\""));
    }

    @Test
    @DisplayName("an empty pool parses to an empty list — that is a valid answer, not a failure")
    void emptyPoolIsNotAFailure() {
        List<Story> stories = CursedStoryPool.parseStories("{\"ok\":true,\"notes\":[]}");
        assertNotNull(stories);
        assertTrue(stories.isEmpty());
    }

    @Test
    @DisplayName("entries missing the fields the book is written from are skipped, not fatal")
    void skipsUnusableEntries() {
        String body = """
            {"ok":true,"notes":[
              {"id":1},
              {"deathCarriage":5},
              {"id":0,"deathCarriage":5},
              "nonsense",
              {"id":9,"deathCarriage":5}
            ]}""";
        List<Story> stories = CursedStoryPool.parseStories(body);
        assertNotNull(stories);
        assertEquals(1, stories.size());
        assertEquals(9, stories.get(0).id());
        assertEquals("", stories.get(0).targetName());   // absent optional field → empty, not null
        assertEquals(0L, stories.get(0).landedTs());
    }
}
