package games.brennan.dungeontrain.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The builder's mirror setting, which is persisted as one packed int and travels over the wire the
 * same way — so a wrong bit is a silently-wrong toggle rather than a crash.
 */
final class BuilderMirrorFlagsTest {

    @Test
    @DisplayName("Every combination survives a pack/unpack round trip")
    void roundTrip() {
        for (int mask = 0; mask < 16; mask++) {
            assertEquals(mask, BuilderMirrorFlags.unpack(mask).pack(), "mask " + mask);
        }
    }

    @Test
    @DisplayName("Axes don't share bits")
    void axesAreIndependent() {
        BuilderMirrorFlags onlyZ = BuilderMirrorFlags.NONE.with("z", true);
        assertTrue(onlyZ.z());
        assertFalse(onlyZ.x());
        assertFalse(onlyZ.y());
        assertFalse(onlyZ.variants());
    }

    @Test
    @DisplayName("Toggling one axis leaves the others alone")
    void togglePreservesTheRest() {
        BuilderMirrorFlags all = new BuilderMirrorFlags(true, true, true, true);
        BuilderMirrorFlags noY = all.with("y", false);
        assertTrue(noY.x());
        assertFalse(noY.y());
        assertTrue(noY.z());
        assertTrue(noY.variants(), "V is not an axis and must not move when one flips");
    }

    @Test
    @DisplayName("Axis names are read loosely — the command passes them through as typed")
    void axisNamesAreTolerant() {
        assertTrue(BuilderMirrorFlags.NONE.with("X", true).x());
        assertTrue(BuilderMirrorFlags.NONE.with(" v ", true).variants());
        assertEquals(BuilderMirrorFlags.NONE, BuilderMirrorFlags.NONE.with("w", true),
                "an unknown axis changes nothing rather than throwing");
        assertEquals(BuilderMirrorFlags.NONE, BuilderMirrorFlags.NONE.with(null, true));
        assertFalse(BuilderMirrorFlags.NONE.isOn(null));
    }

    @Test
    @DisplayName("anyAxis ignores V — the variant opt-in alone mirrors nothing")
    void anyAxisIgnoresVariants() {
        assertFalse(new BuilderMirrorFlags(false, false, false, true).anyAxis());
        assertTrue(new BuilderMirrorFlags(false, true, false, false).anyAxis());
    }
}
