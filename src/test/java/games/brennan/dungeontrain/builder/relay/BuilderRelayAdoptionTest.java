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
                BuilderRelayUpload.adoptionOf(SharedCarriageClient.CallStatus.OK, build("sec"), true));
    }

    @Test
    @DisplayName("somebody else's build, a garbled answer and a secretless row are all 'not yours'")
    void everythingWithoutAClaimIsNotYours() {
        // The relay refuses a fetch whose uuid isn't the build's owner.
        assertEquals(BuilderRelayUpload.Adoption.NOT_YOURS,
                BuilderRelayUpload.adoptionOf(SharedCarriageClient.CallStatus.FORBIDDEN, null, true));
        assertEquals(BuilderRelayUpload.Adoption.NOT_YOURS,
                BuilderRelayUpload.adoptionOf(SharedCarriageClient.CallStatus.ERROR, null, true));
        // OK with no build at all — a shape the client can produce, and not something to publish on.
        assertEquals(BuilderRelayUpload.Adoption.NOT_YOURS,
                BuilderRelayUpload.adoptionOf(SharedCarriageClient.CallStatus.OK, null, true));
        // Proven, and still no secret: a row stored before secrets existed. Nothing to publish with.
        assertEquals(BuilderRelayUpload.Adoption.NOT_YOURS,
                BuilderRelayUpload.adoptionOf(SharedCarriageClient.CallStatus.OK, build(""), true));
    }

    @Test
    @DisplayName("no secret because this client couldn't prove who it is reads as 'unproven', not 'not yours'")
    void secretlessWithoutProofIsUnproven() {
        // The relay withholds the secret from any fetch without a Mojang-checked owner proof (owner
        // uuids are public). On a dedicated server or an offline account no proof can be made, and
        // the player needs telling where it CAN be done rather than that the build isn't theirs.
        assertEquals(BuilderRelayUpload.Adoption.UNPROVEN,
                BuilderRelayUpload.adoptionOf(SharedCarriageClient.CallStatus.OK, build(""), false));
        // A secret that came back anyway (an older relay, before the proof existed) is still adopted.
        assertEquals(BuilderRelayUpload.Adoption.ADOPT,
                BuilderRelayUpload.adoptionOf(SharedCarriageClient.CallStatus.OK, build("sec"), false));
        // Refusals that aren't about the secret keep their own answers.
        assertEquals(BuilderRelayUpload.Adoption.NOT_YOURS,
                BuilderRelayUpload.adoptionOf(SharedCarriageClient.CallStatus.FORBIDDEN, null, false));
    }

    @Test
    @DisplayName("only the signed-in host's own client can prove ownership")
    void onlyTheHostClientCanProve() {
        assertEquals(true, RelayOwnerProof.canProve(true, true));
        assertEquals(false, RelayOwnerProof.canProve(false, true), "a dedicated server holds no access token");
        assertEquals(false, RelayOwnerProof.canProve(true, false), "a LAN guest is not this client's account");
    }

    @Test
    @DisplayName("an id the relay no longer knows is gone, not unowned")
    void unknownIdIsGone() {
        // Worth its own answer: "gone" tells the player their build was evicted or removed, while
        // "not yours" would send them looking for a permission problem that isn't there.
        assertEquals(BuilderRelayUpload.Adoption.GONE,
                BuilderRelayUpload.adoptionOf(SharedCarriageClient.CallStatus.UNKNOWN, null, false));
    }
}
