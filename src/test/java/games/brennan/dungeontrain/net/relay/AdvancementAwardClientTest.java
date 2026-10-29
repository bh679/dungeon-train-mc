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
 * Pins the {@code /advancements/award} wire shape the relay and every shipped jar share. A body the game
 * doesn't understand is "no answer" (null); a malformed entry is skipped rather than guessed at.
 */
final class AdvancementAwardClientTest {

    private static final ResourceLocation SLIMED = ResourceLocation.parse("dungeontrain:secrete_menu/knockback_slime_hit");

    @Test
    @DisplayName("the pending list parses with id and advancement")
    void parseList() {
        List<AdvancementAwardClient.Award> a = AdvancementAwardClient.parse(
            "{\"ok\":true,\"awards\":[{\"id\":\"a_1a2b3c4d5e6f\",\"adv\":\"dungeontrain:secrete_menu/knockback_slime_hit\",\"ts\":1790939523517}]}");
        assertEquals(List.of(new AdvancementAwardClient.Award("a_1a2b3c4d5e6f", SLIMED)), a);
        assertEquals(List.of(), AdvancementAwardClient.parse("{\"ok\":true,\"awards\":[]}"));
    }

    @Test
    @DisplayName("a not-ok or shapeless body is no answer; bad entries are skipped")
    void parseBad() {
        assertNull(AdvancementAwardClient.parse("{\"ok\":false,\"awards\":[]}"));
        assertNull(AdvancementAwardClient.parse("{\"ok\":true}"));
        assertNull(AdvancementAwardClient.parse("[]"));
        List<AdvancementAwardClient.Award> a = AdvancementAwardClient.parse("{\"ok\":true,\"awards\":["
            + "{\"id\":\"\",\"adv\":\"a:b\"},"
            + "{\"id\":\"a_1\",\"adv\":\"Not An Id\"},"
            + "{\"id\":\"a_2\"},"
            + "\"a_3\","
            + "{\"id\":\"a_4\",\"adv\":\"a:b\"}]}");
        assertEquals(List.of(new AdvancementAwardClient.Award("a_4", ResourceLocation.parse("a:b"))), a);
    }

    @Test
    @DisplayName("the ack carries a dashless uuid and the ids")
    void ackBody() {
        UUID player = UUID.fromString("dd529609-c01f-47a0-84cb-653d35980c51");
        JsonObject o = JsonParser.parseString(AdvancementAwardClient.ackBody(player, List.of("a_1", "a_2"))).getAsJsonObject();
        assertEquals("dd529609c01f47a084cb653d35980c51", o.get("uuid").getAsString());
        assertEquals(2, o.getAsJsonArray("ids").size());
        assertTrue(o.getAsJsonArray("ids").toString().contains("a_2"));
        assertEquals("/advancements/award/ack", AdvancementAwardClient.ACK_PATH);
    }
}
