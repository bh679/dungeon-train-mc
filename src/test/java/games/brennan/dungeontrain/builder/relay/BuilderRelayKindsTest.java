package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kind mapping is what keeps a portal room from being served to a train as a carriage.
 *
 * <p>The relay enforces the same rule on its own side, which is the point of pinning this here too:
 * the two halves have to agree on the spelling of every kind or a build is uploaded under a name the
 * lease filter doesn't recognise.</p>
 */
final class BuilderRelayKindsTest {

    @Test
    @DisplayName("every builder store has a relay kind, spelled as the relay stores it")
    void everyKindMaps() {
        assertEquals("carriage", BuilderRelayKinds.idOf(BuilderPhotoPaths.Kind.CARRIAGE));
        assertEquals("contents", BuilderRelayKinds.idOf(BuilderPhotoPaths.Kind.CONTENTS));
        assertEquals("part", BuilderRelayKinds.idOf(BuilderPhotoPaths.Kind.PART));
        assertEquals("track", BuilderRelayKinds.idOf(BuilderPhotoPaths.Kind.TRACK));
        assertEquals("portal_room", BuilderRelayKinds.idOf(BuilderPhotoPaths.Kind.PORTAL_ROOM));
    }

    @Test
    @DisplayName("every relay kind maps back to the store it came from")
    void kindsRoundTrip() {
        for (BuilderPhotoPaths.Kind kind : BuilderPhotoPaths.Kind.values()) {
            assertEquals(kind, BuilderRelayKinds.kindOf(BuilderRelayKinds.idOf(kind)),
                    "a build downloaded as " + kind + " must be filed back in the store it was saved from");
        }
    }

    @Test
    @DisplayName("a kind this build of the mod doesn't know is refused, never guessed at")
    void unknownKindIsNull() {
        assertNull(BuilderRelayKinds.kindOf("statue"));
        assertNull(BuilderRelayKinds.kindOf(""));
        assertNull(BuilderRelayKinds.kindOf(null));
    }

    @Test
    @DisplayName("every kind names the builder mode it is edited in")
    void everyKindHasAMode() {
        assertEquals(BuilderMode.TRAIN_OUTSIDE, BuilderRelayKinds.modeFor(BuilderPhotoPaths.Kind.CARRIAGE));
        assertEquals(BuilderMode.TRAIN_OUTSIDE, BuilderRelayKinds.modeFor(BuilderPhotoPaths.Kind.CARRIAGE_GROUP));
        assertEquals(BuilderMode.INSIDE_CARRIAGE, BuilderRelayKinds.modeFor(BuilderPhotoPaths.Kind.CONTENTS));
        assertEquals(BuilderMode.INSIDE_CARRIAGE, BuilderRelayKinds.modeFor(BuilderPhotoPaths.Kind.PART));
        assertEquals(BuilderMode.TRACKS_TUNNELS, BuilderRelayKinds.modeFor(BuilderPhotoPaths.Kind.TRACK));
        assertEquals(BuilderMode.TRAIN_DIMENSIONS, BuilderRelayKinds.modeFor(BuilderPhotoPaths.Kind.PORTAL_ROOM));
        for (BuilderPhotoPaths.Kind kind : BuilderPhotoPaths.Kind.values()) {
            assertNotNull(BuilderRelayKinds.modeFor(kind), kind + " has nowhere to be opened");
        }
    }

    @Test
    @DisplayName("a missing kind is a carriage, matching the relay column's default")
    void nullIsACarriage() {
        assertEquals("carriage", BuilderRelayKinds.idOf(null));
    }

    @Test
    @DisplayName("only a whole carriage may be submitted to the train")
    void onlyCarriagesJoinTheTrain() {
        assertTrue(BuilderRelayKinds.canJoinTheTrain(BuilderPhotoPaths.Kind.CARRIAGE));
        for (BuilderPhotoPaths.Kind kind : BuilderPhotoPaths.Kind.values()) {
            if (kind == BuilderPhotoPaths.Kind.CARRIAGE) continue;
            assertFalse(BuilderRelayKinds.canJoinTheTrain(kind),
                    kind + " is a piece of something, not a thing a train slot can hold");
        }
    }

    @Test
    @DisplayName("every kind the builder authors may be offered to the operator")
    void everyKindIsSubmittable() {
        for (BuilderPhotoPaths.Kind kind : BuilderPhotoPaths.Kind.values()) {
            assertTrue(BuilderRelayKinds.canSubmitForReview(kind),
                    kind + " is a build a person can look at and accept");
            assertTrue(BuilderRelayKinds.canSubmitForReview(BuilderRelayKinds.idOf(kind)),
                    kind + " must be submittable from the relay's own name for it too");
        }
    }

    @Test
    @DisplayName("a kind this version has never heard of is not submittable")
    void unknownKindIsNotSubmittable() {
        assertFalse(BuilderRelayKinds.canSubmitForReview("something_new"));
        assertFalse(BuilderRelayKinds.canSubmitForReview((String) null));
        assertFalse(BuilderRelayKinds.canSubmitForReview((BuilderPhotoPaths.Kind) null));
    }

    @Test
    @DisplayName("the string form agrees with the enum form, including on nonsense")
    void stringFormAgrees() {
        for (BuilderPhotoPaths.Kind kind : BuilderPhotoPaths.Kind.values()) {
            assertEquals(BuilderRelayKinds.canJoinTheTrain(kind),
                    BuilderRelayKinds.canJoinTheTrain(BuilderRelayKinds.idOf(kind)));
        }
        assertFalse(BuilderRelayKinds.canJoinTheTrain("something_new"));
        assertFalse(BuilderRelayKinds.canJoinTheTrain((String) null));
    }
}
