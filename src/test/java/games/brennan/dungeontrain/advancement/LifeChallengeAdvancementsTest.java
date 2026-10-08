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

class LifeChallengeAdvancementsTest {

    @Test
    @DisplayName("granted at the threshold, never once lost for the life")
    void shouldGrant() {
        assertTrue(LifeChallengeAdvancements.shouldGrant(100, false, 100));
        assertFalse(LifeChallengeAdvancements.shouldGrant(99, false, 100));
        assertFalse(LifeChallengeAdvancements.shouldGrant(5000, true, 100));
    }

    @Test
    @DisplayName("apples: vanilla's three and BetterNether's black apples, plus both edible backpacks; not the seed")
    void apples() {
        for (String id : new String[] {"minecraft:apple", "minecraft:golden_apple", "minecraft:enchanted_golden_apple",
                "betternether:black_apple", "betternether:stalagnate_bowl_apple",
                "ediblebackpacks:edible_backpack", "ediblebackpacks:golden_edible_backpack"}) {
            assertTrue(LifeChallengeAdvancements.isAppleOrBackpack(id), id);
        }
        assertFalse(LifeChallengeAdvancements.isAppleOrBackpack("betternether:black_apple_seed"));
        assertFalse(LifeChallengeAdvancements.isAppleOrBackpack("somemod:pineapple"));
        assertFalse(LifeChallengeAdvancements.isAppleOrBackpack("minecraft:bread"));
    }

    @Test
    @DisplayName("only melon keeps The Last Melon")
    void melons() {
        assertFalse(LifeChallengeAdvancements.breaksMelonDiet("minecraft:melon_slice"));
        assertFalse(LifeChallengeAdvancements.breaksMelonDiet("minecraft:glistering_melon_slice"));
        assertTrue(LifeChallengeAdvancements.breaksMelonDiet("minecraft:bread"));
        assertTrue(LifeChallengeAdvancements.breaksMelonDiet("minecraft:apple"));
    }

    @Test
    @DisplayName("all five can be lost for a life, so the screen greys them out")
    void disqualifiable() {
        assertTrue(LifeDisqualification.disqualifiableIds().containsAll(java.util.List.of(
                LifeChallengeAdvancements.APPLE_A_DAY, LifeChallengeAdvancements.SELF_MEDICATED,
                LifeChallengeAdvancements.LAST_MELON, LifeChallengeAdvancements.NAKED_AND_AFRAID,
                LifeChallengeAdvancements.NAKED_AND_UNAFRAID)));
    }

    @Test
    @DisplayName("JSON: in the Challenges tab, code-granted, thresholds 100 / 1000")
    void json() throws IOException {
        Object[][] want = {{"apple_a_day", "tab_challenges", 100}, {"self_medicated", "apple_a_day", 1000},
                {"last_melon", "tab_challenges", 100}, {"naked_and_afraid", "tab_challenges", 100},
                {"naked_and_unafraid", "naked_and_afraid", 1000}};
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
