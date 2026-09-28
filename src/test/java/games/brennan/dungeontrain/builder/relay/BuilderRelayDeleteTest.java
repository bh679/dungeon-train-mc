package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reading the relay's answer when My Builds' trash button is pressed.
 *
 * <p>Four refusals, and each sends the player somewhere different: a build in use is a question
 * ("delete anyway?"), a build the relay no longer has is already what they wanted, and a build
 * that is not theirs is not a button they should have been able to press. Collapsing them into
 * one "couldn't delete" would be true and useless.</p>
 */
final class BuilderRelayDeleteTest {

    private static SharedCarriageClient.VisibilityResult result(SharedCarriageClient.CallStatus status,
                                                                boolean ok, boolean inUse) {
        return new SharedCarriageClient.VisibilityResult(status, ok, inUse, "");
    }

    @Test
    @DisplayName("ok is deleted")
    void okIsDeleted() {
        assertEquals(BuilderRelayUpload.DeleteOutcome.DELETED,
                BuilderRelayUpload.DeleteOutcome.of(result(SharedCarriageClient.CallStatus.OK, true, false)));
    }

    @Test
    @DisplayName("a build somebody is riding is a question, not a failure")
    void inUseIsAQuestion() {
        assertEquals(BuilderRelayUpload.DeleteOutcome.IN_USE,
                BuilderRelayUpload.DeleteOutcome.of(result(SharedCarriageClient.CallStatus.ERROR, false, true)));
    }

    @Test
    @DisplayName("an id the relay no longer knows is already gone")
    void unknownIsGone() {
        assertEquals(BuilderRelayUpload.DeleteOutcome.GONE,
                BuilderRelayUpload.DeleteOutcome.of(result(SharedCarriageClient.CallStatus.UNKNOWN, false, false)));
        // …and so is a build the secret recovery could not find at all.
        assertEquals(BuilderRelayUpload.DeleteOutcome.GONE,
                BuilderRelayUpload.DeleteOutcome.ofAdoption(BuilderRelayUpload.Adoption.GONE));
    }

    @Test
    @DisplayName("a secret the relay rejects means the build is not this player's")
    void forbiddenIsNotYours() {
        assertEquals(BuilderRelayUpload.DeleteOutcome.NOT_YOURS,
                BuilderRelayUpload.DeleteOutcome.of(result(SharedCarriageClient.CallStatus.FORBIDDEN, false, false)));
        assertEquals(BuilderRelayUpload.DeleteOutcome.NOT_YOURS,
                BuilderRelayUpload.DeleteOutcome.ofAdoption(BuilderRelayUpload.Adoption.NOT_YOURS));
    }

    @Test
    @DisplayName("anything else — a relay that did not answer, or answered nonsense — is a plain failure")
    void everythingElseFails() {
        assertEquals(BuilderRelayUpload.DeleteOutcome.FAILED,
                BuilderRelayUpload.DeleteOutcome.of(result(SharedCarriageClient.CallStatus.ERROR, false, false)));
    }
}
