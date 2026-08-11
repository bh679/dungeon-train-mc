package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderMirrorFlags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two pure halves of a mirror cell's click: which command it sends, and what it guesses the
 * new state is while the server answers.
 *
 * <p>The guess is the part worth pinning. A cell that stays open has to relight itself, so a
 * wrong sense here doesn't misdraw once — it desynchronises the button from the build until the
 * next packet lands, and every click in between sends the wrong direction.</p>
 */
final class BuilderMirrorButtonTest {

    @Test
    @DisplayName("An axis that is off is turned on, and vice versa")
    void commandFollowsTheCurrentState() {
        assertEquals("dungeontrain editor mirror z on", BuilderMirrorButton.commandFor("z", true));
        assertEquals("dungeontrain editor mirror z off", BuilderMirrorButton.commandFor("z", false));
        assertEquals("dungeontrain editor mirror v on", BuilderMirrorButton.commandFor("v", true));
    }

    @Test
    @DisplayName("Toggling one axis leaves the other three alone")
    void optimisticFlipIsPerAxis() {
        BuilderMirrorFlags before = BuilderMirrorFlags.DEFAULT;   // V on, no structural axes
        assertFalse(before.isOn("x"));
        assertTrue(before.isOn("v"));

        BuilderMirrorFlags after = before.with("x", !before.isOn("x"));
        assertTrue(after.isOn("x"), "the clicked axis flips");
        assertTrue(after.isOn("v"), "V survives a click on X");
        assertFalse(after.isOn("y"));
        assertFalse(after.isOn("z"));

        assertEquals(before, after.with("x", false), "flipping back restores the original");
    }

    @Test
    @DisplayName("V starts on, so the first click on it turns it off")
    void variantAxisStartsOn() {
        BuilderMirrorFlags flags = BuilderMirrorFlags.DEFAULT;
        boolean next = !flags.isOn("v");
        assertFalse(next);
        assertEquals("dungeontrain editor mirror v off", BuilderMirrorButton.commandFor("v", next));
    }
}
