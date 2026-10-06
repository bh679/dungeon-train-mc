package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Tribute-spending and photo-count tiers: their triggers, and the thresholds their JSON asks for. */
final class PhotoTierAdvancementsTest {

    private static JsonObject conditions(String name) throws IOException {
        String json = Files.readString(RepoPaths.advancements().resolve("enchiridion/" + name + ".json"), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return root.getAsJsonObject("criteria").getAsJsonObject("done").getAsJsonObject("conditions");
    }

    @Test
    @DisplayName("a Tribute tier matches only its own side, at or above its price")
    void tributeMatching() {
        PhotoTributeTrigger.Instance selfCare = new PhotoTributeTrigger.Instance(Optional.empty(), PhotoTributeTrigger.OWN, 180);
        assertTrue(selfCare.matches(PhotoTributeTrigger.OWN, 180));
        assertTrue(selfCare.matches(PhotoTributeTrigger.OWN, 243));
        assertFalse(selfCare.matches(PhotoTributeTrigger.OWN, 81));
        assertFalse(selfCare.matches(PhotoTributeTrigger.OTHERS, 243));
    }

    @Test
    @DisplayName("a count tier matches only its own category, at or above its threshold")
    void countMatching() {
        PhotoCountTrigger.Instance fifty = new PhotoCountTrigger.Instance(Optional.empty(), "animal", 50);
        assertTrue(fifty.matches("animal", 50));
        assertFalse(fifty.matches("animal", 49));
        assertFalse(fifty.matches("hostile", 500));
    }

    @Test
    @DisplayName("the Tribute tiers ask for the prices agreed: others 3/5/7/9 emeralds, own 20/64/243/729/2187 blocks")
    void tributeThresholds() throws IOException {
        Map<String, Integer> others = new LinkedHashMap<>();
        others.put("supporting_artists", 3);
        others.put("appreciating_photos", 5);
        others.put("voyeur", 7);
        others.put("big_spender", 9);
        Map<String, Integer> ownBlocks = new LinkedHashMap<>();
        ownBlocks.put("self_care", 20);
        ownBlocks.put("confidence", 64);
        ownBlocks.put("i_believe_in_myself", 243);
        ownBlocks.put("self_employed_photographer", 729);
        ownBlocks.put("master_self_endorser", 2187);
        for (Map.Entry<String, Integer> e : others.entrySet()) {
            JsonObject c = conditions(e.getKey());
            assertEquals(PhotoTributeTrigger.OTHERS, c.get("whose").getAsString(), e.getKey());
            assertEquals(e.getValue(), c.get("min_cost").getAsInt(), e.getKey());
        }
        for (Map.Entry<String, Integer> e : ownBlocks.entrySet()) {
            JsonObject c = conditions(e.getKey());
            assertEquals(PhotoTributeTrigger.OWN, c.get("whose").getAsString(), e.getKey());
            assertEquals(e.getValue() * 9, c.get("min_cost").getAsInt(), e.getKey());
        }
    }

    @Test
    @DisplayName("each count tier is 50 / 200 / 500 photos of its category")
    void countThresholds() throws IOException {
        String[][] tiers = {
            {"animal", "wildlife_photographer", "field_guide", "gational_neographic"},
            {"hostile", "danger_close", "creature_feature", "rogues_gallery"},
            {"passenger", "those_we_meet", "inhabitants", "passenger_log"}};
        int[] thresholds = {50, 200, 500};
        for (String[] tier : tiers) {
            for (int i = 0; i < 3; i++) {
                JsonObject c = conditions(tier[i + 1]);
                assertEquals(tier[0], c.get("category").getAsString(), tier[i + 1]);
                assertEquals(thresholds[i], c.get("threshold").getAsInt(), tier[i + 1]);
            }
        }
    }
}
