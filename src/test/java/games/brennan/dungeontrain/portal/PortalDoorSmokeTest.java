package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math tests for {@link PortalDoorSmoke} — where the smoke comes out.
 *
 * <p>The properties worth pinning are the ones a player would notice going wrong and a build would
 * not: smoke coming out of the <i>wrong</i> door (the one back onto the train, which leads nowhere
 * interesting), smoke emitted inside the door block, where physics shoves it straight back out, and
 * smoke that climbs when it was asked to leak.</p>
 */
final class PortalDoorSmokeTest {

    private static final PortalCarriageLayout LAYOUT = new PortalCarriageLayout(9, 7, 7);

    /** Long enough to cover several haze cadences as well as the wisp one. */
    private static final int TICKS = 128;

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
    @DisplayName("every particle starts outside the door's own cell, in the doorway opening")
    void emitsClearOfTheDoorBlock() {
        for (PortalCarriageRole role : PortalCarriageRole.values()) {
            PortalDoorSmoke s = smoke(role);
            int doorX = s.doorX();

            for (long tick = 0; tick < TICKS; tick++) {
                for (PortalDoorSmoke.Emission e : s.emissionsOn(tick)) {
                    String where = e.kind() + " at tick " + tick + " for " + role;

                    // Outside the door cell [doorX, doorX + 1), on the corridor side of it.
                    assertTrue(s.intoCorridor() < 0 ? e.x() < doorX : e.x() >= doorX + 1,
                        "particle spawned inside the door block: " + where);
                    assertTrue(LAYOUT.insideCorridor(e.x(), e.y(), e.z()),
                        "particle spawned outside the corridor: " + where);

                    // In the doorway column, and in the two-block opening rather than in the lintel.
                    assertEquals(LAYOUT.doorZ(), (int) Math.floor(e.z()),
                        "particle left the doorway column: " + where);
                    assertTrue(e.y() > LAYOUT.floorY() + 1 && e.y() < LAYOUT.floorY() + 3,
                        "particle outside the doorway opening: " + where);
                }
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
            for (long tick = 0; tick < TICKS; tick++) {
                for (PortalDoorSmoke.Emission e : s.emissionsOn(tick)) {
                    String where = e.kind() + " at tick " + tick + " for " + role;
                    assertTrue(e.vx() * s.intoCorridor() > 0,
                        "the smoke drifts back through the door: " + where);
                    assertTrue(e.vy() <= 0,
                        "the smoke rises — it should settle, not climb: " + where);
                    assertTrue(Math.abs(e.vx()) > Math.abs(e.vy()),
                        "the smoke should travel mainly outward, not vertically: " + where);
                }
            }
        }
    }

    /**
     * The haze is the body of the effect and has to stay in the frame long enough to overlap itself;
     * the wisps are what leaves. If the haze ever drifted as fast as a wisp it would empty the
     * doorway instead of filling it.
     */
    @Test
    @DisplayName("the haze barely moves compared with the wisps, and sits in the doorway")
    void hazeHangsInTheFrame() {
        PortalDoorSmoke s = smoke(PortalCarriageRole.ENTRY);
        PortalDoorSmoke.Emission wisp = s.wispOn(0L);
        PortalDoorSmoke.Emission haze = s.hazeOn(0L);
        assertNotNull(wisp);
        assertNotNull(haze);

        assertEquals(PortalDoorSmoke.Kind.WISP, wisp.kind());
        assertEquals(PortalDoorSmoke.Kind.HAZE, haze.kind());
        assertTrue(Math.abs(haze.vx()) < Math.abs(wisp.vx()),
            "the haze should hang in the frame, not stream out of it like a wisp");

        // Mid-opening rather than at the floor: it fills the frame the player is looking through.
        assertTrue(haze.y() > LAYOUT.floorY() + 1.5,
            "the haze should sit in the middle of the doorway, not along the floor");
    }

    /** The two kinds are on different cadences, and the wisps are the frequent one. */
    @Test
    @DisplayName("wisps come faster than haze, and neither runs every tick")
    void cadences() {
        PortalDoorSmoke s = smoke(PortalCarriageRole.ENTRY);
        int wisps = 0;
        int hazes = 0;
        for (long tick = 0; tick < TICKS; tick++) {
            if (s.wispOn(tick) != null) wisps++;
            if (s.hazeOn(tick) != null) hazes++;
        }
        assertEquals(TICKS / PortalDoorSmoke.EMIT_INTERVAL_TICKS, wisps);
        assertTrue(hazes > 0, "the haze never emits");
        assertTrue(hazes < wisps, "the haze should be the slower of the two");
        assertEquals(List.of(), s.emissionsOn(1L), "the tick after a wisp is a gap");
    }

    /**
     * The illusion's own requirement: the carriage copy and its twin are fed from one call, so what
     * matters is that the answer is a function of the tick and nothing else — no counter, no random
     * source, nothing that could give the two copies different smoke.
     */
    @Test
    @DisplayName("a tick's emissions are the same every time they are asked for")
    void isAPureFunctionOfTheTick() {
        PortalDoorSmoke s = smoke(PortalCarriageRole.ENTRY);
        for (long tick = 0; tick < 64; tick++) {
            assertEquals(s.emissionsOn(tick), s.emissionsOn(tick),
                "emissions at tick " + tick + " are not reproducible");
        }
    }

    /**
     * Negative game times are not hypothetical here — the cadence is driven by the level's tick
     * counter, and {@code %} on a negative left operand is negative in Java, which would leave a
     * corridor emitting on a different cadence (or picking a negative frame point) on such a world.
     */
    @Test
    @DisplayName("the cadence survives a negative tick counter")
    void handlesNegativeTicks() {
        PortalDoorSmoke s = smoke(PortalCarriageRole.ENTRY);
        int emitted = 0;
        for (long tick = -TICKS; tick < 0; tick++) {
            for (PortalDoorSmoke.Emission e : s.emissionsOn(tick)) {
                emitted++;
                assertTrue(LAYOUT.insideCorridor(e.x(), e.y(), e.z()),
                    "particle spawned outside the corridor at tick " + tick);
            }
        }
        assertTrue(emitted > 0, "nothing emitted at all on negative ticks");
    }
}
