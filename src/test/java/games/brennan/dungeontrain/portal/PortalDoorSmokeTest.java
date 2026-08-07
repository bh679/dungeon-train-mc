package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math tests for {@link PortalDoorSmoke} — where the seep comes out.
 *
 * <p>The two properties worth pinning are the ones a player would notice going wrong: smoke coming
 * out of the <i>wrong</i> door (the one back onto the train, which leads nowhere interesting), and
 * smoke emitted inside the door block, where physics shoves it straight back out. Neither shows up
 * in a build.</p>
 */
final class PortalDoorSmokeTest {

    private static final PortalCarriageLayout LAYOUT = new PortalCarriageLayout(9, 7, 7);

    private static PortalDoorSmoke smoke(PortalCarriageRole role) {
        return new PortalDoorSmoke(LAYOUT, role);
    }

    @Test
    @DisplayName("the smoking door is the one on the twin side of the midpoint, for both roles")
    void smokesThePortalWardDoor() {
        assertEquals(LAYOUT.farDoorX(), smoke(PortalCarriageRole.ENTRY).doorX(),
            "an ENTRY corridor reaches the room through its far door");
        assertEquals(LAYOUT.nearDoorX(), smoke(PortalCarriageRole.EXIT).doorX(),
            "an EXIT corridor reaches the room through its near door");

        // Restated as the side-of-the-midpoint rule PortalFrames swaps on, rather than as the two
        // door names above: the smoking door must be the one a player is already in the twin by the
        // time they reach. ENTRY puts the twin past the midpoint, EXIT before it.
        assertTrue(smoke(PortalCarriageRole.ENTRY).doorX() > LAYOUT.midX(),
            "an ENTRY corridor's twin lies past the midpoint");
        assertTrue(smoke(PortalCarriageRole.EXIT).doorX() < LAYOUT.midX(),
            "an EXIT corridor's twin lies before the midpoint");
    }

    @Test
    @DisplayName("every particle starts outside the door's own cell, inside the corridor")
    void emitsClearOfTheDoorBlock() {
        for (PortalCarriageRole role : PortalCarriageRole.values()) {
            PortalDoorSmoke s = smoke(role);
            int doorX = s.doorX();

            for (long tick = 0; tick < 64; tick++) {
                PortalDoorSmoke.Emission e = s.emissionOn(tick);
                if (e == null) continue;

                // Outside the door cell [doorX, doorX + 1), on the corridor side of it.
                assertTrue(s.intoCorridor() < 0 ? e.x() < doorX : e.x() >= doorX + 1,
                    "particle spawned inside the door block at tick " + tick + " for " + role);
                assertTrue(LAYOUT.insideCorridor(e.x(), e.y(), e.z()),
                    "particle spawned outside the corridor at tick " + tick + " for " + role);

                // In the doorway column, and in the two-block opening rather than in the lintel.
                assertEquals(LAYOUT.doorZ(), (int) Math.floor(e.z()),
                    "particle left the doorway column at tick " + tick + " for " + role);
                assertTrue(e.y() > LAYOUT.floorY() + 1 && e.y() < LAYOUT.floorY() + 3,
                    "particle outside the doorway opening at tick " + tick + " for " + role);
            }
        }
    }

    /**
     * Both halves of "comes out of the door, and does not go up". The vertical one is the easier of
     * the two to lose: it is one sign, and getting it wrong turns a leak into a chimney.
     */
    @Test
    @DisplayName("the drift carries the smoke out into the corridor, and never upward")
    void driftsOutAndNotUp() {
        for (PortalCarriageRole role : PortalCarriageRole.values()) {
            PortalDoorSmoke s = smoke(role);
            for (long tick = 0; tick < 16; tick++) {
                PortalDoorSmoke.Emission e = s.emissionOn(tick);
                if (e == null) continue;
                assertTrue(e.vx() * s.intoCorridor() > 0,
                    "the seep drifts back through the door at tick " + tick + " for " + role);
                assertTrue(e.vy() <= 0,
                    "the seep rises at tick " + tick + " for " + role + " — it should settle, not climb");
                assertTrue(Math.abs(e.vx()) > Math.abs(e.vy()),
                    "the seep should travel mainly outward, not vertically, for " + role);
            }
        }
    }

    /**
     * The illusion's own requirement: the carriage copy and its twin are fed from one call, so what
     * matters is that the answer is a function of the tick and nothing else — no counter, no random
     * source, nothing that could give the two copies different smoke.
     */
    @Test
    @DisplayName("a tick's emission is the same every time it is asked for")
    void isAPureFunctionOfTheTick() {
        PortalDoorSmoke s = smoke(PortalCarriageRole.ENTRY);
        for (long tick = 0; tick < 32; tick++) {
            assertEquals(s.emissionOn(tick), s.emissionOn(tick),
                "emission at tick " + tick + " is not reproducible");
        }
    }

    @Test
    @DisplayName("it seeps on a fixed cadence rather than every tick")
    void seepsRatherThanPours() {
        PortalDoorSmoke s = smoke(PortalCarriageRole.ENTRY);
        int emitted = 0;
        for (long tick = 0; tick < 40; tick++) {
            if (s.emissionOn(tick) != null) emitted++;
        }
        assertEquals(40 / PortalDoorSmoke.EMIT_INTERVAL_TICKS, emitted);
        assertNull(s.emissionOn(1L), "the tick after an emission is a gap");
    }

    /**
     * Negative game times are not hypothetical here — the emission cadence is driven by the level's
     * tick counter, and {@code %} on a negative left operand is negative in Java, which would leave a
     * corridor emitting on a different cadence (or picking a negative frame point) on such a world.
     */
    @Test
    @DisplayName("the cadence survives a negative tick counter")
    void handlesNegativeTicks() {
        PortalDoorSmoke s = smoke(PortalCarriageRole.ENTRY);
        for (long tick = -40; tick < 0; tick++) {
            PortalDoorSmoke.Emission e = s.emissionOn(tick);
            if (e == null) continue;
            assertTrue(LAYOUT.insideCorridor(e.x(), e.y(), e.z()),
                "particle spawned outside the corridor at tick " + tick);
        }
    }
}
