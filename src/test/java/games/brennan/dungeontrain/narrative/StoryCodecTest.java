package games.brennan.dungeontrain.narrative;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks down {@link StoryCodec}'s optional {@code weight} field — the knob that defers a narrative
 * series without removing it from the lectern rotation.
 */
final class StoryCodecTest {

    private static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("dungeontrain", "narratives/stories/test_story");

    private static InputStream json(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    private static final String ONE_LETTER =
        """
        {"character":"Nobody","story":"Untitled"%s,
         "letters":[{"index":1,"label":"Letter One","variants":["body"]}]}""";

    @Test
    @DisplayName("a fractional weight survives the parse")
    void parsesFractionalWeight() throws Exception {
        StoryFile story = StoryCodec.parse(json(ONE_LETTER.formatted(",\"weight\":0.1")), ID);
        assertEquals(0.1, story.weight(), 1e-9);
    }

    @Test
    @DisplayName("a missing weight defaults to the 1.0 baseline — every other shipped story")
    void defaultsToBaseline() throws Exception {
        StoryFile story = StoryCodec.parse(json(ONE_LETTER.formatted("")), ID);
        assertEquals(1.0, story.weight(), 1e-9);
    }

    @Test
    @DisplayName("parseWeight reads the weight alone, defaulting when the field is absent")
    void parseWeightReadsJustTheField() throws Exception {
        assertEquals(0.1, StoryCodec.parseWeight(json(ONE_LETTER.formatted(",\"weight\":0.1"))), 1e-9);
        assertEquals(1.0, StoryCodec.parseWeight(json(ONE_LETTER.formatted(""))), 1e-9);
    }

    @Test
    @DisplayName("withWeight rebuilds rather than mutating")
    void withWeightRebuilds() throws Exception {
        StoryFile story = StoryCodec.parse(json(ONE_LETTER.formatted("")), ID);
        StoryFile deferred = story.withWeight(0.1);
        assertEquals(1.0, story.weight(), 1e-9);
        assertEquals(0.1, deferred.weight(), 1e-9);
        assertEquals(story.letters(), deferred.letters());
    }
}
