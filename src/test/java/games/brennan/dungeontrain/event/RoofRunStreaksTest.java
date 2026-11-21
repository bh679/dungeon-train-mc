package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.RoofRunStreaks.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoofRunStreaksTest {

    private static final long P = 10;
    private static final int GS = 4;
    private static final UUID RUNNER = UUID.randomUUID();
    private static final Object WORLD_A = new Object();
    private static final Object WORLD_B = new Object();

    private RoofRunStreaks streaks;

    @BeforeEach
    void setUp() {
        streaks = new RoofRunStreaks(P);
    }

    private OptionalInt roof(Object level, int anchor, long now) {
        return streaks.observe(RUNNER, level, Kind.ON_ROOF, anchor, GS, now);
    }

    private OptionalInt pad(Object level, long now) {
        return streaks.observe(RUNNER, level, Kind.ON_TOP_OTHER, 0, 1, now);
    }

    @Test
    void oneRoofThenThePadEarnsOne() {
        assertTrue(roof(WORLD_A, 100, 1000).isEmpty());
        assertEquals(OptionalInt.of(1), pad(WORLD_A, 1010));
    }

    @Test
    void threeGroupsAlongTheRoofsEarnThree() {
        roof(WORLD_A, 100, 1000);
        roof(WORLD_A, 104, 1010);
        pad(WORLD_A, 1020);
        roof(WORLD_A, 108, 1030);
        assertEquals(OptionalInt.of(3), roof(WORLD_A, 112, 1040));
    }

    @Test
    void droppingInsideEndsTheRun() {
        roof(WORLD_A, 100, 1000);
        streaks.observe(RUNNER, WORLD_A, Kind.BELOW_ROOF, 0, 1, 1010);
        assertFalse(streaks.has(RUNNER));
        assertTrue(pad(WORLD_A, 1020).isEmpty());
    }

    @Test
    void aMissedScanRestartsTheRun() {
        roof(WORLD_A, 100, 1000);
        assertTrue(roof(WORLD_A, 104, 1020).isEmpty(), "gap of two periods starts fresh at 104");
        assertEquals(OptionalInt.of(1), roof(WORLD_A, 108, 1030));
    }

    @Test
    void thePadNeverStartsARun() {
        assertTrue(pad(WORLD_A, 1000).isEmpty());
        assertTrue(pad(WORLD_A, 1010).isEmpty());
        assertFalse(streaks.has(RUNNER));
    }

    /** The reported exploit: a run left in the dying world paid out on the next world's spawn pad. */
    @Test
    void aRunFromTheLastWorldDoesNotPayOnTheNextWorldsPad() {
        roof(WORLD_A, 100, 2000);
        roof(WORLD_A, 112, 2010);
        // New world, same JVM and UUID, its clock reaching the old lastScan + one period.
        assertTrue(pad(WORLD_B, 2020).isEmpty());
        assertTrue(roof(WORLD_B, 500, 2020).isEmpty());
    }

    @Test
    void serverStopClearsEveryRun() {
        roof(WORLD_A, 100, 2000);
        streaks.clearAll();
        assertTrue(pad(WORLD_A, 2010).isEmpty());
    }

    @Test
    void deathOrRespawnClearsTheRun() {
        roof(WORLD_A, 100, 2000);
        roof(WORLD_A, 108, 2010);
        streaks.clear(RUNNER);
        assertTrue(pad(WORLD_A, 2020).isEmpty());
    }

    @Test
    void playersWhoLeftAreDropped() {
        roof(WORLD_A, 100, 2000);
        streaks.retainOnly(Set.of());
        assertFalse(streaks.has(RUNNER));
    }
}
