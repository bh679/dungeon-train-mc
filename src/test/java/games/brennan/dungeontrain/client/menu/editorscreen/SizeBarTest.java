package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The tile's size bar: a share of the upload limit, coloured by how close it is. */
final class SizeBarTest {

    private static final int MAX = BuilderRelayUpload.MAX_BLOCKS_CHARS;

    @Test
    @DisplayName("the bar is the stored size over the upload limit, clamped to a full bar")
    void fraction() {
        assertEquals(0F, SizeBar.fraction(0));
        assertEquals(0F, SizeBar.fraction(-5));
        assertEquals(0.5F, SizeBar.fraction(MAX / 2), 1e-6);
        assertEquals(1F, SizeBar.fraction(MAX));
        assertEquals(1F, SizeBar.fraction(MAX * 2L), "past the limit is a full bar, not more");
        assertEquals(0.787F, SizeBar.fraction(543_128L), 0.001F, "the largest live build");
    }

    @Test
    @DisplayName("green under half, amber to 85%, red from there")
    void colour() {
        assertEquals(SizeBar.GREEN, SizeBar.colour(0.49F));
        assertEquals(SizeBar.AMBER, SizeBar.colour(0.5F));
        assertEquals(SizeBar.AMBER, SizeBar.colour(0.84F));
        assertEquals(SizeBar.RED, SizeBar.colour(0.85F));
        assertEquals(SizeBar.RED, SizeBar.colour(1F));
    }
}
