package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reading the relay's answer when My Builds submits a build this world holds no secret for.
 *
 * <p>The case is ordinary rather than exotic: the title-screen reconcile restores builds to the relay
 * before any world is loaded, so it has nowhere to write the secrets it gets back, and a build
 * uploaded from one world is listed in every world. Submitting those used to say "this world didn't
 * upload that build" — true, and useless to the person looking at their own build.</p>
 */
final class BuilderRelayAdoptionTest {

    private static SharedCarriageClient.BuildFetch build(String secret) {
        // The trailing "" is the sidecar document. Adoption turns on the secret alone, so an empty
        // one is the honest fixture here — and a build fetched from a relay predating the field
        // carries exactly that.
        return new SharedCarriageClient.BuildFetch(7, "portal_room", "", "library", "", "profile",
                "BLOCKS", 16, 8, 16, 0, List.of(), secret, "");
    }

    @Test
    @DisplayName("a build of this player's that came back with its secret is adopted")
    void ownBuildIsAdopted() {
        assertEquals(BuilderRelayUpload.Adoption.ADOPT,
                BuilderRelayUpload.adoptionOf(SharedCarriageClient.CallStatus.OK, build("sec")));
    }

    @Test
    @DisplayName("somebody else's build, a garbled answer and a secretless row are all 'not yours'")
    void everythingWithoutAClaimIsNotYours() {
        // The relay authorises the fetch on the owner's uuid, so FORBIDDEN is the ownership check.
        assertEquals(BuilderRelayUpload.Adoption.NOT_YOURS,
                BuilderRelayUpload.adoptionOf(SharedCarriageClient.CallStatus.FORBIDDEN, null));
        assertEquals(BuilderRelayUpload.Adoption.NOT_YOURS,
                BuilderRelayUpload.adoptionOf(SharedCarriageClient.CallStatus.ERROR, null));
        // OK with no build at all — a shape the client can produce, and not something to publish on.
        assertEquals(BuilderRelayUpload.Adoption.NOT_YOURS,
                BuilderRelayUpload.adoptionOf(SharedCarriageClient.CallStatus.OK, null));
        // An older relay, or a row stored before secrets existed: nothing to authorise a publish with.
        assertEquals(BuilderRelayUpload.Adoption.NOT_YOURS,
                BuilderRelayUpload.adoptionOf(SharedCarriageClient.CallStatus.OK, build("")));
    }

    @Test
    @DisplayName("an id the relay no longer knows is gone, not unowned")
    void unknownIdIsGone() {
        // Worth its own answer: "gone" tells the player their build was evicted or removed, while
        // "not yours" would send them looking for a permission problem that isn't there.
        assertEquals(BuilderRelayUpload.Adoption.GONE,
                BuilderRelayUpload.adoptionOf(SharedCarriageClient.CallStatus.UNKNOWN, null));
    }
}
