package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OneSittingStreakTest {

    @Test
    void streakIsDistanceFromBaseline() {
        assertEquals(0, OneSittingStreak.streak(500, 500));
        assertEquals(3000, OneSittingStreak.streak(500, 3500));
    }

    @Test
    void streakNeverNegative() {
        assertEquals(0, OneSittingStreak.streak(500, 20));
    }

    @Test
    void newLifeRebasesToTheLowerReading() {
        assertEquals(0, OneSittingStreak.rebaseIfReset(2400, 0));
        assertEquals(12, OneSittingStreak.rebaseIfReset(2400, 12));
    }

    @Test
    void forwardProgressKeepsBaseline() {
        assertEquals(2400, OneSittingStreak.rebaseIfReset(2400, 2401));
        assertEquals(2400, OneSittingStreak.rebaseIfReset(2400, 2400));
    }

    @Test
    void advancementJsonListensForTheStreakAction() throws Exception {
        String path = "/data/dungeontrain/advancement/dungeon_train/one_sitting.json";
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertNotNull(in, path);
            JsonObject conditions = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("criteria").getAsJsonObject("action")
                .getAsJsonObject("conditions");
            assertEquals(OneSittingStreak.ACTION_ID, conditions.get("actionId").getAsString());
            assertEquals(OneSittingStreak.DEFAULT_CARRIAGES, conditions.get("threshold").getAsInt());
        }
    }
}
