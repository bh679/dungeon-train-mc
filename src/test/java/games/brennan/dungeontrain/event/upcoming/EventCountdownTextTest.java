package games.brennan.dungeontrain.event.upcoming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.ComponentSerialization;
import org.junit.jupiter.api.Test;

/**
 * The English fallbacks sent with each event line (what a client on an older Dungeon Train shows)
 * must say exactly what en_us says, and cover every key the line uses.
 */
class EventCountdownTextTest {

    @Test
    void fallbacksMatchEnUs() throws Exception {
        String raw = Files.readString(
            RepoPaths.root().resolve("src/main/resources/assets/dungeontrain/lang/en_us.json"), StandardCharsets.UTF_8);
        JsonObject enUs = JsonParser.parseString(raw).getAsJsonObject();
        Map<String, String> inLang = new TreeMap<>();
        for (String key : enUs.keySet()) {
            if (key.startsWith(EventCountdownText.PREFIX)) inLang.put(key, enUs.get(key).getAsString());
        }
        assertEquals(inLang, new TreeMap<>(EventCountdownText.ENGLISH));
    }

    /**
     * The fallback survives the trip a chat packet takes (the component codec) and is what a client
     * with no DT lang keys reads. The test JVM's language holds vanilla's keys only, like such a client.
     */
    @Test
    void clientWithoutTheKeysReadsEnglish() {
        assertFalse(Language.getInstance().has(EventCountdownText.PREFIX + "in_1h"), "test language must lack DT keys");
        UpcomingEvent event = new UpcomingEvent("e", "Build Night", 1L, "https://twitch.tv/x", null);
        Component sent = EventCountdownText.line(event, EventCountdown.Stage.HOUR);
        Tag wire = ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, sent).getOrThrow();
        Component received = ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, wire).getOrThrow();
        assertEquals("[Event] Build Night starts in 1 hour. [Join] [Details]", received.getString());
    }

    @Test
    void everyStageHasAFallback() {
        for (EventCountdown.Stage stage : EventCountdown.Stage.values()) {
            String key = EventCountdownText.PREFIX + stage.key;
            assertEquals(true, EventCountdownText.ENGLISH.containsKey(key), key);
        }
    }
}
