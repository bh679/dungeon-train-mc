package games.brennan.dungeontrain.compat.photo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PhotoTierNeedTest {

    private static final String SUPPORTING = "enchiridion/supporting_artists";
    private static final String APPRECIATING = "enchiridion/appreciating_photos";
    private static final String VOYEUR = "enchiridion/voyeur";

    private static OptionalInt nextWith(String... earned) {
        return PhotoTierNeed.nextCost(Set.of(earned)::contains);
    }

    @Test
    @DisplayName("a player needs the first tier they have not earned")
    void needsFirstUnearned() {
        assertEquals(OptionalInt.of(3), nextWith());
        assertEquals(OptionalInt.of(5), nextWith(SUPPORTING));
        assertEquals(OptionalInt.of(7), nextWith(SUPPORTING, APPRECIATING));
    }

    @Test
    @DisplayName("a player with the whole chain needs nothing")
    void noNeedAfterVoyeur() {
        assertEquals(OptionalInt.empty(), nextWith(SUPPORTING, APPRECIATING, VOYEUR));
    }

    @Test
    @DisplayName("the query value holds distinct prices, lowest first, and is blank for none")
    void param() {
        assertEquals("5,7", PhotoTierNeed.param(List.of(7, 5, 7)));
        assertEquals("7", PhotoTierNeed.param(List.of(7)));
        assertEquals("", PhotoTierNeed.param(List.of()));
    }

    @Test
    @DisplayName("each tier's price is the one its advancement JSON asks for, on other people's photos")
    void costsMatchTheJson() throws IOException {
        for (PhotoTierNeed.Tier tier : PhotoTierNeed.TIERS) {
            String resource = "/data/dungeontrain/advancement/" + tier.path() + ".json";
            try (InputStream in = PhotoTierNeedTest.class.getResourceAsStream(resource)) {
                assertNotNull(in, resource);
                JsonObject criteria = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("criteria");
                JsonObject conditions = criteria.entrySet().iterator().next().getValue()
                        .getAsJsonObject().getAsJsonObject("conditions");
                assertEquals("others", conditions.get("whose").getAsString(), resource);
                assertEquals(tier.cost(), conditions.get("min_cost").getAsInt(), resource);
            }
        }
    }
}
