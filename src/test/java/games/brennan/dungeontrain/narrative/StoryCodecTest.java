package games.brennan.dungeontrain.narrative;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down {@link StoryCodec}'s optional serving-order tuning: the {@code deferred} hold-back tier
 * that keeps a series out of the lectern rotation until every ordinary series has been read, the
 * {@code after} prerequisite that chains one series behind another, and the start-pick {@code weight}.
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
    @DisplayName("the deferred flag survives the parse")
    void parsesDeferred() throws Exception {
        StoryFile story = StoryCodec.parse(json(ONE_LETTER.formatted(",\"deferred\":true")), ID);
        assertTrue(story.deferred());
    }

    @Test
    @DisplayName("a missing flag defaults to not-deferred — every other shipped story")
    void defaultsToOrdinary() throws Exception {
        StoryFile story = StoryCodec.parse(json(ONE_LETTER.formatted("")), ID);
        assertFalse(story.deferred());
    }

    @Test
    @DisplayName("after and weight survive the parse")
    void parsesAfterAndWeight() throws Exception {
        StoryFile story = StoryCodec.parse(
            json(ONE_LETTER.formatted(",\"after\":\"pip\",\"weight\":20")), ID);
        assertEquals("pip", story.after());
        assertEquals(20.0, story.weight());
    }

    @Test
    @DisplayName("missing after/weight default to no prerequisite and baseline weight")
    void defaultsAfterAndWeight() throws Exception {
        StoryFile story = StoryCodec.parse(json(ONE_LETTER.formatted("")), ID);
        assertNull(story.after());
        assertEquals(1.0, story.weight());
    }

    @Test
    @DisplayName("a negative or non-numeric weight falls back to baseline; blank after is no prerequisite")
    void badTuningFallsBack() throws Exception {
        assertEquals(1.0, StoryCodec.parse(json(ONE_LETTER.formatted(",\"weight\":-3")), ID).weight());
        assertEquals(1.0, StoryCodec.parse(json(ONE_LETTER.formatted(",\"weight\":\"lots\"")), ID).weight());
        assertEquals(0.0, StoryCodec.parse(json(ONE_LETTER.formatted(",\"weight\":0")), ID).weight());
        assertNull(StoryCodec.parse(json(ONE_LETTER.formatted(",\"after\":\"\"")), ID).after());
    }

    @Test
    @DisplayName("parseTuning reads the tuning alone, defaulting when it is absent")
    void parseTuningReadsJustTheFields() throws Exception {
        assertEquals(new StoryFile.Tuning(true, "pip", 4.0), StoryCodec.parseTuning(
            json(ONE_LETTER.formatted(",\"deferred\":true,\"after\":\"pip\",\"weight\":4"))));
        assertEquals(new StoryFile.Tuning(false, null, 1.0),
            StoryCodec.parseTuning(json(ONE_LETTER.formatted(""))));
    }

    @Test
    @DisplayName("withTuning rebuilds rather than mutating")
    void withTuningRebuilds() throws Exception {
        StoryFile story = StoryCodec.parse(json(ONE_LETTER.formatted("")), ID);
        StoryFile held = story.withTuning(new StoryFile.Tuning(true, "pip", 3.0));
        assertFalse(story.deferred());
        assertNull(story.after());
        assertTrue(held.deferred());
        assertEquals("pip", held.after());
        assertEquals(3.0, held.weight());
        assertEquals(story.letters(), held.letters());
    }
}
