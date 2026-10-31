package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.client.LoadingStories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which lines the death perks menu tells, and how long each holds. */
class DeathStoryTest {

    private static final PerkMenuSnapshot POINTED =
        new PerkMenuSnapshot(Map.of("health", 3, "speed", 2), Map.of("health", 3, "speed", 1), 4, 2);
    private static final PerkMenuSnapshot NO_POINTS = new PerkMenuSnapshot(Map.of("health", 3), Map.of(), 4, 0);

    @Test
    @DisplayName("every beat has its line, each held for the loading screen's time for it")
    void fullStory() {
        DeathStory s = DeathStory.of(new int[] {4, 7}, POINTED, "en_us");
        for (int i = 0; i < MenuReveal.BEATS; i++) {
            assertNotNull(s.line(i), "beat " + i);
            assertEquals(LoadingStories.lineMs(s.line(i).getString()), s.beats()[i], "beat " + i);
        }
    }

    @Test
    @DisplayName("no points this life: no active line and its beat takes no time")
    void noActive() {
        DeathStory s = DeathStory.of(new int[] {1, 1}, NO_POINTS, "en_us");
        assertNull(s.line(MenuReveal.BEAT_ACTIVE));
        assertEquals(0, s.beats()[MenuReveal.BEAT_ACTIVE]);
    }

    @Test
    @DisplayName("no stats from the server: no earned line, the stars hold the shortest time")
    void noStats() {
        DeathStory s = DeathStory.of(null, NO_POINTS, "en_us");
        assertNull(s.line(MenuReveal.BEAT_STARS));
        assertEquals(LoadingStories.minLineMs(), s.beats()[MenuReveal.BEAT_STARS]);
        assertNotNull(s.line(MenuReveal.BEAT_SELECT));
    }

    @Test
    @DisplayName("the line out of range is none")
    void outOfRange() {
        DeathStory s = DeathStory.of(null, NO_POINTS, "en_us");
        assertNull(s.line(-1));
        assertNull(s.line(MenuReveal.BEATS));
    }
}
