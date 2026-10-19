package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code /advancements/revoke} wire shape the relay and every shipped jar share. A body the
 * game doesn't understand is "no answer" (null), never "nothing revoked"; a malformed entry is
 * skipped rather than guessed at.
 */
final class AdvancementRevokeClientTest {

    private static final ResourceLocation ALPHA = ResourceLocation.parse("dungeontrain:secrete_menu/reversed_alpha");

    @Test
    @DisplayName("the active list parses with id, advancement and time")
    void parseList() {
        List<AdvancementRevokeClient.Revoke> r = AdvancementRevokeClient.parse(
            "{\"ok\":true,\"revokes\":[{\"id\":\"r_1a2b3c4d5e6f\",\"adv\":\"dungeontrain:secrete_menu/reversed_alpha\",\"ts\":1790939523517}]}");
        assertEquals(List.of(new AdvancementRevokeClient.Revoke("r_1a2b3c4d5e6f", ALPHA, 1790939523517L)), r);
        assertEquals(List.of(), AdvancementRevokeClient.parse("{\"ok\":true,\"revokes\":[]}"));
    }

    @Test
    @DisplayName("a not-ok or shapeless body is no answer; bad entries are skipped")
    void parseBad() {
        assertNull(AdvancementRevokeClient.parse("{\"ok\":false,\"revokes\":[]}"));
        assertNull(AdvancementRevokeClient.parse("{\"ok\":true}"));
        assertNull(AdvancementRevokeClient.parse("[]"));
        List<AdvancementRevokeClient.Revoke> r = AdvancementRevokeClient.parse("{\"ok\":true,\"revokes\":["
            + "{\"id\":\"\",\"adv\":\"a:b\",\"ts\":1},"
            + "{\"id\":\"r_1\",\"adv\":\"Not An Id\",\"ts\":1},"
            + "{\"id\":\"r_2\",\"adv\":\"a:b\"},"
            + "{\"id\":\"r_3\",\"adv\":\"a:b\",\"ts\":\"soon\"},"
            + "{\"id\":\"r_4\",\"adv\":\"a:b\",\"ts\":0},"
            + "{\"id\":\"r_5\",\"adv\":\"a:b\",\"ts\":7}]}");
        assertEquals(List.of(new AdvancementRevokeClient.Revoke("r_5", ResourceLocation.parse("a:b"), 7)), r);
    }

    @Test
    @DisplayName("an entry round-trips through the cache's JSON form")
    void jsonRoundTrip() {
        AdvancementRevokeClient.Revoke r = new AdvancementRevokeClient.Revoke("r_1", ALPHA, 42);
        assertEquals(r, AdvancementRevokeClient.revoke(AdvancementRevokeClient.json(r)));
    }

    @Test
    @DisplayName("the ack carries a dashless uuid and the ids")
    void ackBody() {
        UUID player = UUID.fromString("dd529609-c01f-47a0-84cb-653d35980c51");
        JsonObject o = JsonParser.parseString(AdvancementRevokeClient.ackBody(player, List.of("r_1", "r_2"))).getAsJsonObject();
        assertEquals("dd529609c01f47a084cb653d35980c51", o.get("uuid").getAsString());
        assertEquals(2, o.getAsJsonArray("ids").size());
        assertTrue(o.getAsJsonArray("ids").toString().contains("r_2"));
    }
}
