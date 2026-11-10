package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A restore is applied only when the relay signed it, for this player, and it hasn't expired —
 * anything a relay of the player's own choosing could make up is ignored.
 */
final class RestoreSignatureTest {

    private static final UUID PLAYER = UUID.fromString("8b4b39cf-fbcf-4d68-b97e-d6bd951b69b6");
    private static final String BARE = "8b4b39cffbcf4d68b97ed6bd951b69b6";
    private static final String RESTORE = "{\"id\":\"r_1\",\"inventory\":[{\"slot\":0,\"id\":\"minecraft:diamond_sword\","
        + "\"count\":1,\"nbt\":\"{id:\\\"minecraft:diamond_sword\\\",count:1}\"}],\"armor\":[],\"offhand\":[],"
        + "\"run\":{\"x\":9140.6,\"runState\":\"{travelledCarriageIndex:237}\",\"longWayBackMaxCarriage\":237}}";

    private static KeyPair keys() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String body(KeyPair k, String uuid, long exp, String restore) throws Exception {
        String signed = "{\"v\":1,\"uuid\":\"" + uuid + "\",\"exp\":" + exp + ",\"restore\":" + restore + "}";
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(k.getPrivate());
        s.update(signed.getBytes(StandardCharsets.UTF_8));
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("signed", signed);
        o.addProperty("sig", Base64.getEncoder().encodeToString(s.sign()));
        return o.toString();
    }

    @Test
    @DisplayName("the baked-in relay key decodes")
    void relayKeyDecodes() {
        assertNotNull(RestoreSignature.relayKey());
    }

    @Test
    @DisplayName("a signed, unexpired restore for this player parses, run block included")
    void signedRestoreParses() throws Exception {
        KeyPair k = keys();
        InventoryRestoreClient.Restore r = InventoryRestoreClient.parseSigned(body(k, BARE, 2_000, RESTORE), PLAYER, 1_000, k.getPublic());
        assertNotNull(r);
        assertEquals("r_1", r.id());
        assertEquals(1, r.inventory().size());
        assertEquals(9140.6, r.run().x());
    }

    @Test
    @DisplayName("another key, another player, an expired answer or a tampered one are all ignored")
    void forgeriesIgnored() throws Exception {
        KeyPair k = keys();
        KeyPair other = keys();
        assertNull(InventoryRestoreClient.parseSigned(body(other, BARE, 2_000, RESTORE), PLAYER, 1_000, k.getPublic()));
        assertNull(InventoryRestoreClient.parseSigned(body(k, "00000000000000000000000000000001", 2_000, RESTORE), PLAYER, 1_000, k.getPublic()));
        assertNull(InventoryRestoreClient.parseSigned(body(k, BARE, 999, RESTORE), PLAYER, 1_000, k.getPublic()));
        String tampered = body(k, BARE, 2_000, RESTORE).replace("237", "999");
        assertNull(InventoryRestoreClient.parseSigned(tampered, PLAYER, 1_000, k.getPublic()));
    }

    @Test
    @DisplayName("an unsigned answer is ignored, even a well-formed one")
    void unsignedIgnored() throws Exception {
        String unsigned = "{\"ok\":true,\"restore\":" + RESTORE + "}";
        assertNull(InventoryRestoreClient.parseSigned(unsigned, PLAYER, 1_000, keys().getPublic()));
        assertNull(InventoryRestoreClient.parseSigned(unsigned, PLAYER, 1_000, RestoreSignature.relayKey()));
    }
}
