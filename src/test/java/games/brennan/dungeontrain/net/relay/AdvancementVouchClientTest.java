package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The vouch request and its answer, and the unverified-earn report it pairs with. */
class AdvancementVouchClientTest {

    private static final UUID PLAYER = UUID.fromString("7b7774bd-61fc-44b5-9b8b-3f38ce70a443");
    private static final String A = "dungeontrain:dungeon_train/carts_100";
    private static final String B = "dungeontrain:dungeon_train/chests_100_unique";

    @Test
    @DisplayName("the request names the player, the difficulty and every id held on trust")
    void bodyCarriesIds() {
        JsonObject body = AdvancementVouchClient.body(PLAYER, "hard", List.of(A, B));
        assertEquals("7b7774bd61fc44b59b8b3f38ce70a443", body.get("uuid").getAsString());
        assertEquals("hard", body.get("difficulty").getAsString());
        assertEquals(2, body.getAsJsonArray("ids").size());
    }

    @Test
    @DisplayName("only ids that were asked about come back; junk answers are nothing")
    void answerIsSubsetOfAsked() {
        Set<String> asked = Set.of(A, B);
        assertEquals(List.of(A), AdvancementVouchClient.parse(
            "{\"ok\":true,\"ids\":[\"" + A + "\",\"dungeontrain:dungeon_train/completionist\",\"" + A + "\"]}", asked));
        assertTrue(AdvancementVouchClient.parse("{\"ok\":false,\"ids\":[\"" + A + "\"]}", asked).isEmpty());
        assertTrue(AdvancementVouchClient.parse("{\"ok\":true}", asked).isEmpty());
        assertTrue(AdvancementVouchClient.parse("[]", asked).isEmpty());
    }

    @Test
    @DisplayName("an unverified derived grant is reported with how much the profile holds on trust")
    void unverifiedReportBody() {
        JsonObject body = AdvancementFirstClient.unverifiedBody(PLAYER, "AshSeven",
            "dungeontrain:dungeon_train/completionist", "normal", 275);
        assertEquals("dungeontrain:dungeon_train/completionist", body.get("id").getAsString());
        assertEquals("AshSeven", body.get("name").getAsString());
        assertEquals(275, body.get("onTrust").getAsInt());
        assertEquals("normal", body.get("difficulty").getAsString());
    }
}
