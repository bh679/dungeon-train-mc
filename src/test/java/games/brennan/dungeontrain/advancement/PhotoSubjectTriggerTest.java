package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code photo_subject} conditions: one key, or several keys in the same photo. */
final class PhotoSubjectTriggerTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static PhotoSubjectTrigger.Instance parse(String json) {
        return PhotoSubjectTrigger.Instance.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    @DisplayName("a single subject matches any photo showing it")
    void single() {
        PhotoSubjectTrigger.Instance warden = parse("{\"subject\":\"entity:minecraft:warden\"}");
        assertTrue(warden.matches(Set.of("entity:minecraft:warden", "cave")));
        assertFalse(warden.matches(Set.of("entity:minecraft:wither")));
    }

    @Test
    @DisplayName("subjects need every key in the one photo")
    void allInOnePhoto() {
        PhotoSubjectTrigger.Instance trio = parse(
                "{\"subjects\":[\"selfie\",\"entity:minecraft:warden\",\"entity:minecraft:wither\"]}");
        assertTrue(trio.matches(Set.of("selfie", "entity:minecraft:warden", "entity:minecraft:wither", "cave")));
        assertFalse(trio.matches(Set.of("entity:minecraft:warden", "entity:minecraft:wither")));
        assertFalse(trio.matches(Set.of("selfie", "entity:minecraft:warden")));
    }

    @Test
    @DisplayName("a condition with neither field is refused")
    void emptyRefused() {
        assertTrue(PhotoSubjectTrigger.Instance.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}")).isError());
        assertTrue(PhotoSubjectTrigger.Instance.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"subjects\":[]}")).isError());
    }

    @Test
    @DisplayName("both shapes survive a round trip")
    void roundTrip() {
        for (String json : new String[] {"{\"subject\":\"pet_own\"}", "{\"subjects\":[\"selfie\",\"echo\"]}"}) {
            PhotoSubjectTrigger.Instance parsed = parse(json);
            JsonElement encoded = PhotoSubjectTrigger.Instance.CODEC.encodeStart(JsonOps.INSTANCE, parsed).getOrThrow();
            assertEquals(parsed, parse(encoded.toString()));
        }
    }
}
