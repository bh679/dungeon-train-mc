package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
