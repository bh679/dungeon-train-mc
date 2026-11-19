package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The vault-find claim's body and the relay's answer: fail closed on anything but a real number. */
class VaultFindClientTest {

    private static final UUID CLAIM = UUID.fromString("00000000-0000-0000-0000-0000000000cd");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000ab");

    @Test
    @DisplayName("the body carries the claim id, item, bare uuid, name and vault type")
    void body() {
        JsonObject body = VaultFindClient.body(CLAIM, PLAYER, "Steve", "minecraft:mace", "ominous");
        assertEquals("000000000000000000000000000000cd", body.get("claimId").getAsString());
        assertEquals("000000000000000000000000000000ab", body.get("uuid").getAsString());
        assertEquals("minecraft:mace", body.get("itemId").getAsString());
        assertEquals("Steve", body.get("name").getAsString());
        assertEquals("ominous", body.get("vault").getAsString());
        assertTrue(body.has("modVersion"));
    }

    @Test
    @DisplayName("an answer with a number is read; the first ever is number 1")
    void parsesAnswer() {
        assertEquals(new VaultFindClient.Answer(true, 1),
            VaultFindClient.parse("{\"ok\":true,\"first\":true,\"serial\":1,\"by\":{\"name\":\"Steve\"}}"));
        assertEquals(new VaultFindClient.Answer(false, 37),
            VaultFindClient.parse("{\"ok\":true,\"first\":false,\"serial\":37}"));
    }

    @Test
    @DisplayName("no number, a zero number or a refusal is no answer at all")
    void failsClosed() {
        assertNull(VaultFindClient.parse("{\"ok\":true,\"first\":true}"));
        assertNull(VaultFindClient.parse("{\"ok\":true,\"first\":false,\"serial\":0}"));
        assertNull(VaultFindClient.parse("{\"ok\":false,\"first\":true,\"serial\":1}"));
        assertNull(VaultFindClient.parse("{\"error\":\"bad_claim\"}"));
        assertNull(VaultFindClient.parse("[]"));
    }
}
