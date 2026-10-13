package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllAtOnceAdvancementTest {

    @Test
    @DisplayName("granted at the threshold, never once any rule is broken for the life")
    void shouldGrant() {
        assertTrue(AllAtOnceAdvancement.shouldGrant(100, 100, false));
        assertFalse(AllAtOnceAdvancement.shouldGrant(99, 100, false));
        assertFalse(AllAtOnceAdvancement.shouldGrant(5000, 100, true));
    }

    @Test
    @DisplayName("both tiers can be lost for a life, so the screen greys them out")
    void disqualifiable() {
        assertTrue(LifeDisqualification.disqualifiableIds().containsAll(AllAtOnceAdvancement.TIERS));
    }

    @Test
    @DisplayName("the individual challenges keep their own rules — the new tiers are separate entries")
    void individualChallengesUnchanged() {
        assertFalse(LifeDisqualification.streakIds().contains(AllAtOnceAdvancement.ALL_AT_ONCE));
        assertFalse(AllAtOnceAdvancement.TIERS.contains(LifeDisqualification.NO_BREAK_100));
    }

    @Test
    @DisplayName("JSON: All at Once in the Challenges tab at 100, The Challenge below it at 1000")
    void json() throws IOException {
        Object[][] want = {{"all_at_once", "tab_challenges", 100}, {"the_challenge", "all_at_once", 1000}};
        for (Object[] w : want) {
            JsonObject adv = JsonParser.parseString(Files.readString(
                    RepoPaths.advancements().resolve("dungeon_train/" + w[0] + ".json"))).getAsJsonObject();
            assertEquals("dungeontrain:dungeon_train/" + w[1], adv.get("parent").getAsString(), (String) w[0]);
            JsonObject c = adv.getAsJsonObject("criteria").getAsJsonObject("challenge");
            assertEquals("dungeontrain:code_granted", c.get("trigger").getAsString());
            assertEquals((int) w[2], c.getAsJsonObject("conditions").get("threshold").getAsInt());
        }
    }
}
