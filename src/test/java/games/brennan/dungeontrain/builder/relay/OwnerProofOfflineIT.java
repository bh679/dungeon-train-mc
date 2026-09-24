package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline end-to-end: the mod's real relay client against a LOCAL dp-relay whose Mojang session
 * server is a stand-in. Opt-in — needs DUNGEONTRAIN_RELAY_BASE_URL (local relay + cap) and
 * DT_MOCK_MOJANG (the stand-in's base URL, whose POST /join records what a real joinServer would).
 */
@EnabledIfEnvironmentVariable(named = "DT_MOCK_MOJANG", matches = ".+")
final class OwnerProofOfflineIT {

    private static final String RELAY = System.getenv("DUNGEONTRAIN_RELAY_BASE_URL");
    private static final String MOJANG = System.getenv("DT_MOCK_MOJANG");

    /** What OwnerProofJoin.join does against Mojang, done against the stand-in. */
    private static void mockJoin(UUID uuid, String name, String serverId) throws Exception {
        String q = "uuid=" + uuid + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&serverId=" + serverId;
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(MOJANG + "/join?" + q))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, r.statusCode());
    }

    @Test
    void secretOnlyReachesAProvenOwner() throws Exception {
        UUID owner = UUID.randomUUID();
        String name = "Dev";
        String blocks = "OFFLINE-" + owner;

        // The owner's own world uploads: it gets its secret, as ever.
        SharedCarriageClient.BuildUpload up = SharedCarriageClient.submitBuild(owner.toString(), name, blocks,
                7, 5, 5, "", "stone", "", "carriage", "", "offline_cabin", "profile", "").join().orElseThrow();
        assertFalse(up.secret().isEmpty(), "uploader gets the secret");
        System.out.println("[IT] submitted id=" + up.id());

        // 1. Anyone with the (public) owner uuid: blocks yes, secret no → UNPROVEN.
        SharedCarriageClient.FetchResult bare = SharedCarriageClient.fetchBuild(up.id(), owner.toString(), RELAY).join();
        assertEquals(SharedCarriageClient.CallStatus.OK, bare.status());
        assertEquals(blocks, bare.build().blocks(), "previews still get blocks");
        assertEquals("", bare.build().secret(), "no secret on a bare uuid");
        assertEquals(BuilderRelayUpload.Adoption.UNPROVEN, BuilderRelayUpload.adoptionOf(bare.status(), bare.build(), false));
        System.out.println("[IT] bare-uuid fetch: blocks ok, secret withheld -> UNPROVEN");

        // 2. A challenge that nobody joined with (an attacker's forged proof).
        String forgedId = SharedCarriageClient.ownerProofChallenge(owner.toString(), RELAY).join();
        assertEquals(40, forgedId.length());
        SharedCarriageClient.FetchResult forged = SharedCarriageClient.fetchBuild(up.id(), owner.toString(), RELAY,
                new SharedCarriageClient.OwnerProof(name, forgedId)).join();
        assertEquals("", forged.build().secret(), "no join on record → no secret");
        System.out.println("[IT] forged proof: secret withheld");

        // 3. Re-uploading the identical build under the owner's uuid (the dedupe leak).
        SharedCarriageClient.BuildUpload dup = SharedCarriageClient.submitBuild(owner.toString(), name, blocks,
                7, 5, 5, "", "stone", "", "carriage", "", "offline_cabin", "profile", "").join().orElseThrow();
        assertEquals(up.id(), dup.id());
        assertTrue(dup.deduped());
        assertEquals("", dup.secret(), "dedupe hands back no secret");
        assertEquals("", dup.token(), "builder dedupe hands back no lease");
        System.out.println("[IT] dedupe re-upload: no secret, no lease");

        // 4. The real owner: challenge → their client joins → proven fetch returns the secret → ADOPT.
        String serverId = SharedCarriageClient.ownerProofChallenge(owner.toString(), RELAY).join();
        mockJoin(owner, name, serverId);
        SharedCarriageClient.OwnerProof proof = new SharedCarriageClient.OwnerProof(name, serverId);
        SharedCarriageClient.FetchResult proven = SharedCarriageClient.fetchBuild(up.id(), owner.toString(), RELAY, proof).join();
        assertEquals(up.secret(), proven.build().secret(), "proven owner recovers the secret");
        assertEquals(BuilderRelayUpload.Adoption.ADOPT, BuilderRelayUpload.adoptionOf(proven.status(), proven.build(), true));
        // …and the same proof keeps working for a burst of actions (no second Mojang call needed).
        assertEquals(up.secret(), SharedCarriageClient.fetchBuild(up.id(), owner.toString(), RELAY, proof).join().build().secret());
        System.out.println("[IT] proven owner: secret recovered -> ADOPT (proof reusable)");

        // 5. A proof for this owner is useless against someone else's build.
        UUID other = UUID.randomUUID();
        SharedCarriageClient.BuildUpload theirs = SharedCarriageClient.submitBuild(other.toString(), "Other", "OTHER-" + other,
                7, 5, 5, "", "stone", "", "carriage", "", "their_cabin", "profile", "").join().orElseThrow();
        SharedCarriageClient.FetchResult cross = SharedCarriageClient.fetchBuild(theirs.id(), other.toString(), RELAY, proof).join();
        assertEquals("", cross.build().secret(), "a proof for one uuid never unlocks another's build");
        System.out.println("[IT] owner's proof on another player's build: secret withheld");

        // 6. The recovered secret really is write authority; a guess is not.
        String secret = proven.build().secret();
        assertEquals(SharedCarriageClient.CallStatus.OK,
                SharedCarriageClient.ownerSave(up.id(), secret, blocks + "-v2", "", 0).join());
        assertTrue(SharedCarriageClient.publish(up.id(), secret, true).join().ok());
        assertEquals(SharedCarriageClient.CallStatus.FORBIDDEN,
                SharedCarriageClient.deleteBuild(up.id(), "0".repeat(32), false).join().status());
        assertTrue(SharedCarriageClient.deleteBuild(up.id(), secret, false).join().ok());
        System.out.println("[IT] recovered secret: owner-save ok, publish ok, guessed delete 403, owner delete ok");
    }
}
