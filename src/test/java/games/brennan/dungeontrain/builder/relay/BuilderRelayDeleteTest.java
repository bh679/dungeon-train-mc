package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reading the relay's refusal when My Builds' trash button is pressed.
 *
 * <p>Three refusals, and each sends the player somewhere different: a build in use is worth a retry,
 * a build the relay no longer has is already what they wanted, and a build that is not theirs is
 * not a button they should have been able to press. Collapsing them into one "couldn't delete"
 * would be true and useless.</p>
 */
final class BuilderRelayDeleteTest {

    private static String keyOf(Component message) {
        return ((TranslatableContents) message.getContents()).getKey();
    }

    private static SharedCarriageClient.VisibilityResult result(SharedCarriageClient.CallStatus status,
                                                                boolean ok, boolean inUse) {
        return new SharedCarriageClient.VisibilityResult(status, ok, inUse, "");
    }

    @Test
    @DisplayName("a build somebody is riding is 'in use', the same words a refused withdraw uses")
    void inUseIsARetry() {
        assertEquals("gui.dungeontrain.builder.profile.in_use_withdraw",
                keyOf(BuilderRelayUpload.deleteRefusal(result(SharedCarriageClient.CallStatus.ERROR, false, true))));
    }

    @Test
    @DisplayName("an id the relay no longer knows is already gone, not a failure")
    void unknownIsGone() {
        assertEquals("gui.dungeontrain.builder.profile.gone_short",
                keyOf(BuilderRelayUpload.deleteRefusal(result(SharedCarriageClient.CallStatus.UNKNOWN, false, false))));
    }

    @Test
    @DisplayName("a secret the relay rejects means the build is not this player's")
    void forbiddenIsNotYours() {
        assertEquals("gui.dungeontrain.builder.profile.not_yours",
                keyOf(BuilderRelayUpload.deleteRefusal(result(SharedCarriageClient.CallStatus.FORBIDDEN, false, false))));
    }

    @Test
    @DisplayName("anything else — a relay that did not answer, or answered nonsense — is a plain failure")
    void everythingElseFails() {
        assertEquals("gui.dungeontrain.builder.profile.delete_failed",
                keyOf(BuilderRelayUpload.deleteRefusal(result(SharedCarriageClient.CallStatus.ERROR, false, false))));
    }
}
