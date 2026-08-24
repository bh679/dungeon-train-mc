package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import games.brennan.dungeontrain.client.localization.edit.OverlayLanguage;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Deaths;
import games.brennan.dungeontrain.train.SharedCarriageMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import games.brennan.dungeontrain.util.PresenceLine;

/**
 * Renders the count-dependent lines all the way to TEXT against the shipped {@code ru_ru.json}.
 *
 * <p>Every other test here asserts the translation KEY the server picked, which is the right unit to
 * check a rule with — but a key is not what a player reads. These lines nest a clause inside a
 * sentence ("\u0417\u0434\u0435\u0441\u044c \u043f\u043e\u0433\u0438\u0431\u043b\u043e %s: %s"), and nothing proved the two halves compose into a
 * grammatical Russian sentence rather than into a stray key or a doubled noun. This does, by
 * installing the real lang file through {@link Language#inject} and calling
 * {@link Component#getString()}.</p>
 */
final class RussianDeclensionRenderTest {

    private final Language original = Language.getInstance();

    @AfterEach
    void restoreLanguage() {
        Language.inject(original);
    }

    /** Install the shipped ru_ru lang file as the active language. */
    private static void useRussian() throws IOException {
        Path file = RepoPaths.root().resolve("src/main/resources/assets/dungeontrain/lang/ru_ru.json");
        JsonObject json = JsonParser.parseString(
                Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, String> entries = new HashMap<>();
        for (var e : json.entrySet()) {
            entries.put(e.getKey(), e.getValue().getAsString());
        }
        Language.inject(new OverlayLanguage(Language.getInstance(), entries));
    }

    private static RandomSource rng() {
        return RandomSource.create(1234L);
    }

    @Test @DisplayName("Elapsed time renders as grammatical Russian at every plural boundary")
    void elapsedTimeRenders() throws IOException {
        useRussian();
        assertEquals("1 \u043c\u0438\u043d\u0443\u0442\u0443", render(1));
        assertEquals("2 \u043c\u0438\u043d\u0443\u0442\u044b", render(2));
        assertEquals("5 \u043c\u0438\u043d\u0443\u0442", render(5));
        assertEquals("11 \u043c\u0438\u043d\u0443\u0442", render(11));   // the teens are 'many'
        assertEquals("21 \u043c\u0438\u043d\u0443\u0442\u0443", render(21));   // …and 21 is 'one' again
        assertEquals("22 \u043c\u0438\u043d\u0443\u0442\u044b", render(22));
    }

    private static String render(long minutes) {
        return PresenceLine.agoComponent("ru_ru", Duration.ofMinutes(minutes)).getString();
    }

    /**
     * The composed sentence, which is the thing a key-level assertion cannot see: the nested count
     * clause has to land inside the outer line as a declined noun phrase, with no leftover noun and
     * no raw key.
     */
    @Test @DisplayName("The shared-carriage death line composes into one Russian sentence")
    void deathLineComposes() throws IOException {
        useRussian();
        String two = SharedCarriageMessage.deathLine("ru_ru", new Deaths(List.of("Ann"), 2), rng())
                .getString();
        String five = SharedCarriageMessage.deathLine("ru_ru", new Deaths(List.of("Ann"), 5), rng())
                .getString();
        // Same random seed, so the same phrasing variant \u2014 only the declension differs. Both the
        // nested count clause AND the "\u0438 \u0435\u0449\u0451 N" wrapper decline, which is the whole composition.
        assertEquals("2 \u043f\u0443\u0442\u043d\u0438\u043a\u0430 \u0442\u0430\u043a \u0438 \u043d\u0435 \u0432\u044b\u0448\u043b\u0438 \u0438\u0437 \u044d\u0442\u043e\u0433\u043e \u0432\u0430\u0433\u043e\u043d\u0430: Ann \u0438 \u0435\u0449\u0451 1 \u0434\u0440\u0443\u0433\u043e\u0439.", two);
        assertEquals("5 \u043f\u0443\u0442\u043d\u0438\u043a\u043e\u0432 \u0442\u0430\u043a \u0438 \u043d\u0435 \u0432\u044b\u0448\u043b\u0438 \u0438\u0437 \u044d\u0442\u043e\u0433\u043e \u0432\u0430\u0433\u043e\u043d\u0430: Ann \u0438 \u0435\u0449\u0451 4 \u0434\u0440\u0443\u0433\u0438\u0445.", five);
    }
}
