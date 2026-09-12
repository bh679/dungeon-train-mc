package games.brennan.dungeontrain.narrative;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line that names who built a dimensional carriage.
 *
 * <p>Assertions read the translation key + arguments rather than rendered text: no language is
 * loaded in unit tests, so {@code getString()} would only ever return the key back.</p>
 */
class PortalBuilderMessageTest {

    private static final String KEY = "chat.dungeontrain.portal_builder.";

    private static TranslatableContents contents(Component c) {
        assertTrue(c.getContents() instanceof TranslatableContents, "expected a translatable line");
        return (TranslatableContents) c.getContents();
    }

    @Test
    @DisplayName("Every stranger variant is reachable, and each carries the builder's name")
    void everyVariantNamesTheBuilder() {
        RandomSource rng = RandomSource.create(42L);
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            TranslatableContents t = contents(PortalBuilderMessage.random(rng, "Faulthurst", false));
            assertTrue(t.getKey().startsWith(KEY), t.getKey());
            assertFalse(t.getKey().endsWith("self"), "a stranger's build must never read as yours");
            assertEquals(1, t.getArgs().length);
            assertEquals("Faulthurst", ((Component) t.getArgs()[0]).getString());
            keys.add(t.getKey());
        }
        assertEquals(6, keys.size(), keys.toString());
    }

    @Test
    @DisplayName("Your own build gets the single self line, with nobody named")
    void ownBuildIsTheSelfLine() {
        TranslatableContents t = contents(PortalBuilderMessage.random(RandomSource.create(1L), "Faulthurst", true));
        assertEquals(KEY + "self", t.getKey());
        assertEquals(0, t.getArgs().length);
    }

    @Test
    @DisplayName("A blank name falls back to 'someone' rather than nothing")
    void blankNameFallsBack() {
        for (String bad : new String[]{"", "   ", null}) {
            TranslatableContents t = contents(PortalBuilderMessage.random(RandomSource.create(7L), bad, false));
            assertEquals("someone", ((Component) t.getArgs()[0]).getString(), "for [" + bad + "]");
        }
    }

    @Test
    @DisplayName("Formatting codes in a name are stripped before it reaches chat")
    void formattingCodesAreStripped() {
        TranslatableContents t = contents(PortalBuilderMessage.random(RandomSource.create(7L), "§kFault§lhurst", false));
        String shown = ((Component) t.getArgs()[0]).getString();
        assertFalse(shown.contains("§"), shown);
        assertTrue(shown.contains("Fault"), shown);
    }
}
