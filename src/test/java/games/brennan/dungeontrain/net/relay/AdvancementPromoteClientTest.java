package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.advancement.AdvancementPromotion;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the {@code /advancements/promote} wire shape the relay and every shipped jar share. Anything the
 * game doesn't fully understand — no promote, an unknown difficulty, an unknown mode — must parse to
 * "nothing to offer", never to a guess.
 */
final class AdvancementPromoteClientTest {

    @Test
    @DisplayName("a pending promote parses with its id, target and mode")
    void parsePromote() {
        AdvancementPromoteClient.Promote p = AdvancementPromoteClient.parse(
            "{\"ok\":true,\"promote\":{\"id\":\"p_1a2b\",\"target\":\"hard\",\"mode\":\"copy\"}}");
        assertEquals(new AdvancementPromoteClient.Promote("p_1a2b", "hard", AdvancementPromotion.Mode.COPY), p);
    }

    @Test
    @DisplayName("no promote, a bad target, a bad mode or a not-ok body all mean nothing to offer")
    void parseNothing() {
        assertNull(AdvancementPromoteClient.parse("{\"ok\":true,\"promote\":null}"));
        assertNull(AdvancementPromoteClient.parse("{\"ok\":true}"));
        assertNull(AdvancementPromoteClient.parse("{\"ok\":false,\"promote\":{\"id\":\"p\",\"target\":\"hard\",\"mode\":\"copy\"}}"));
        assertNull(AdvancementPromoteClient.parse("{\"ok\":true,\"promote\":{\"id\":\"p\",\"target\":\"insane\",\"mode\":\"copy\"}}"));
        assertNull(AdvancementPromoteClient.parse("{\"ok\":true,\"promote\":{\"id\":\"p\",\"target\":\"hard\",\"mode\":\"swap\"}}"));
        assertNull(AdvancementPromoteClient.parse("{\"ok\":true,\"promote\":{\"id\":\"\",\"target\":\"hard\",\"mode\":\"move\"}}"));
        assertNull(AdvancementPromoteClient.parse("[]"));
    }

    @Test
    @DisplayName("the ack carries a dashless uuid, the state and both id lists")
    void ackBody() {
        UUID player = UUID.fromString("380df991-f603-344c-a090-369bad2a924a");
        JsonObject o = JsonParser.parseString(AdvancementPromoteClient.ackBody(player, "p_1", true,
            List.of(ResourceLocation.parse("minecraft:story/mine_stone")),
            List.of(ResourceLocation.parse("dungeontrain:dungeon_train/carts_100")))).getAsJsonObject();

        assertEquals("380df991f603344ca090369bad2a924a", o.get("uuid").getAsString());
        assertEquals("p_1", o.get("id").getAsString());
        assertEquals("done", o.get("state").getAsString());
        assertEquals("minecraft:story/mine_stone", o.getAsJsonArray("copied").get(0).getAsString());
        assertEquals("dungeontrain:dungeon_train/carts_100", o.getAsJsonArray("moved").get(0).getAsString());
        assertEquals("declined", JsonParser.parseString(AdvancementPromoteClient.ackBody(player, "p_1", false,
            List.of(), List.of())).getAsJsonObject().get("state").getAsString());
    }
}
