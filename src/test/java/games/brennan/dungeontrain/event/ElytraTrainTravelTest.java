package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for the two decisions behind "gliding past the train is not travelling on
 * it": {@link BoardingProgressEvents#insideInterior} (is this position genuinely inside a carriage,
 * one block in from every face?) and {@link BoardingProgressEvents#chooseLeader} (a glider never
 * leads while somebody standing on the train could). No Minecraft bootstrap — the carriage box and
 * the fall-flying flag are supplied as arguments and verified in-game.
 *
 * <p>The roof cases are the point of the file. Boarding detection pads a carriage 3 blocks above
 * its roof so sprint-jumps aren't lost, so a pilot skimming the deck reads as boarded; the interior
 * test is what stops that scan paying out distance and difficulty.</p>
 */
final class ElytraTrainTravelTest {

    /** A carriage-sized box: 10 long, 6 tall, 8 wide, at a non-zero origin. */
    private static boolean inside(double px, double py, double pz) {
        return BoardingProgressEvents.insideInterior(100.0, 64.0, -20.0, 110.0, 70.0, -12.0,
            px, py, pz);
    }

    @Test
    @DisplayName("dead centre of a carriage is inside")
    void centreIsInside() {
        assertTrue(inside(105.0, 67.0, -16.0));
    }

    @Test
    @DisplayName("standing on the roof is not inside")
    void roofIsOutside() {
        assertFalse(inside(105.0, 70.0, -16.0));
        assertFalse(inside(105.0, 73.0, -16.0)); // sprint-jump height: boarded, still not inside
    }

    @Test
    @DisplayName("half a block in from a face is not inside")
    void halfBlockInIsOutside() {
        assertFalse(inside(100.5, 67.0, -16.0));
        assertFalse(inside(105.0, 67.0, -12.5));
        assertFalse(inside(105.0, 64.5, -16.0));
    }

    @Test
    @DisplayName("exactly one block in from every face is inside")
    void exactlyOneBlockInIsInside() {
        assertTrue(inside(101.0, 65.0, -19.0));
        assertTrue(inside(109.0, 69.0, -13.0));
    }

    @Test
    @DisplayName("a box too thin to have an interior contains nothing")
    void thinBoxHasNoInterior() {
        assertFalse(BoardingProgressEvents.insideInterior(0.0, 0.0, 0.0, 10.0, 1.0, 10.0,
            5.0, 0.5, 5.0));
    }

    private static final UUID WALKER = UUID.nameUUIDFromBytes("walker".getBytes());
    private static final UUID PILOT = UUID.nameUUIDFromBytes("pilot".getBytes());

    /** Gliders first in iteration order, so a naive "take the first entry" would pick one. */
    private static Map<UUID, Integer> boarded() {
        Map<UUID, Integer> map = new LinkedHashMap<>();
        map.put(PILOT, 7);
        map.put(WALKER, 3);
        return map;
    }

    @Test
    @DisplayName("a walking player is preferred over a glider")
    void prefersNonGlider() {
        Map.Entry<UUID, Integer> chosen =
            BoardingProgressEvents.chooseLeader(boarded(), Set.of(PILOT));
        assertEquals(WALKER, chosen.getKey());
        assertEquals(3, chosen.getValue());
    }

    @Test
    @DisplayName("a glider leads when every boarded player is one")
    void fallsBackToGlider() {
        Map.Entry<UUID, Integer> chosen =
            BoardingProgressEvents.chooseLeader(boarded(), Set.of(PILOT, WALKER));
        assertEquals(PILOT, chosen.getKey());
    }

    @Test
    @DisplayName("nobody boarded means no leader")
    void emptyMeansNoLeader() {
        assertNull(BoardingProgressEvents.chooseLeader(Map.of(), Set.of()));
    }
}
