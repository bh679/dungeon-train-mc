package games.brennan.dungeontrain.narrative;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Locks down {@link RandomBookCodec}'s handling of the fractional {@code weight} field — the knob
 * that lets a meta book (a donation or how-it-works ask) sit below the 1.0 baseline without being
 * switched off entirely.
 */
final class RandomBookCodecTest {

    private static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("dungeontrain", "narratives/random_books/test_book");

    private static InputStream json(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a fractional weight survives the parse")
    void parsesFractionalWeight() throws Exception {
        RandomBookFile book = RandomBookCodec.parse(json("""
            {"title":"Meta","weight":0.1,"variants":["one"]}"""), ID);
        assertEquals(0.1, book.weight(), 1e-9);
    }

    @Test
    @DisplayName("an integer weight still parses — every shipped file uses one")
    void parsesIntegerWeight() throws Exception {
        RandomBookFile book = RandomBookCodec.parse(json("""
            {"title":"Core","weight":3,"variants":["one"]}"""), ID);
        assertEquals(3.0, book.weight(), 1e-9);
    }

    @Test
    @DisplayName("a missing weight defaults to the 1.0 baseline")
    void defaultsToBaseline() throws Exception {
        RandomBookFile book = RandomBookCodec.parse(json("""
            {"title":"Bare","variants":["one"]}"""), ID);
        assertEquals(1.0, book.weight(), 1e-9);
    }

    @Test
    @DisplayName("a negative weight is rejected rather than silently clamped")
    void rejectsNegativeWeight() {
        assertThrows(IllegalArgumentException.class, () -> RandomBookCodec.parse(json("""
            {"title":"Bad","weight":-2,"variants":["one"]}"""), ID));
    }

    @Test
    @DisplayName("parseWeight reads the weight alone, defaulting when the field is absent")
    void parseWeightReadsJustTheField() throws Exception {
        assertEquals(0.1, RandomBookCodec.parseWeight(json("""
            {"title":"Meta","weight":0.1,"variants":["one"]}""")), 1e-9);
        assertEquals(1.0, RandomBookCodec.parseWeight(json("""
            {"title":"Bare","variants":["one"]}""")), 1e-9);
    }

    @Test
    @DisplayName("withWeight rebuilds rather than mutating")
    void withWeightRebuilds() throws Exception {
        RandomBookFile book = RandomBookCodec.parse(json("""
            {"title":"Meta","weight":1,"variants":["one"]}"""), ID);
        RandomBookFile retuned = book.withWeight(0.1);
        assertEquals(1.0, book.weight(), 1e-9);
        assertEquals(0.1, retuned.weight(), 1e-9);
        assertEquals(book.title(), retuned.title());
        assertEquals(book.variants(), retuned.variants());
    }
}
