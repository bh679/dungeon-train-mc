package games.brennan.dungeontrain.narrative;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down {@link StoryCodec}'s optional {@code deferred} flag — the hold-back tier that keeps a
 * series out of the lectern rotation until every ordinary series has been read.
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
    @DisplayName("parseDeferred reads the flag alone, defaulting when it is absent")
    void parseDeferredReadsJustTheField() throws Exception {
        assertTrue(StoryCodec.parseDeferred(json(ONE_LETTER.formatted(",\"deferred\":true"))));
        assertFalse(StoryCodec.parseDeferred(json(ONE_LETTER.formatted(""))));
    }

    @Test
    @DisplayName("withDeferred rebuilds rather than mutating")
    void withDeferredRebuilds() throws Exception {
        StoryFile story = StoryCodec.parse(json(ONE_LETTER.formatted("")), ID);
        StoryFile held = story.withDeferred(true);
        assertFalse(story.deferred());
        assertTrue(held.deferred());
        assertEquals(story.letters(), held.letters());
    }
}
