package games.brennan.dungeontrain.client.localization.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which Preview button the translation editor offers for a string. Pure — the screen hands in
 * {@link ButtonKeyRecorder#seen}, the tests hand in a set.
 */
class TranslationPreviewKindTest {

    private static final Predicate<String> NOTHING_SEEN = key -> false;

    private static TranslationUnit lang(String key) {
        return new TranslationUnit(TranslationUnit.Type.LANG, "dungeontrain", key,
            "English", "Deutsch", false, false);
    }

    private static TranslationUnit book(String id) {
        return new TranslationUnit(TranslationUnit.Type.BOOK, "dungeontrain", id,
            "Once upon a time", "Es war einmal", false, false);
    }

    @Test
    @DisplayName("narrative book fields and book.* keys preview as a book")
    void books() {
        assertEquals(TranslationPreviewKind.BOOK,
            TranslationPreviewKind.of(book("random_books/deathnote#variants.0"), NOTHING_SEEN));
        assertEquals(TranslationPreviewKind.BOOK,
            TranslationPreviewKind.of(lang("book.dungeontrain.statbook.open.0"), NOTHING_SEEN));
    }

    @Test
    @DisplayName("chat, command feedback and death messages preview as chat")
    void chat() {
        for (String key : new String[] {"chat.dungeontrain.welcome", "commands.dungeontrain.done",
            "command.dungeontrain.usage", "death.attack.dungeontrain.train"}) {
            assertEquals(TranslationPreviewKind.CHAT, TranslationPreviewKind.of(lang(key), NOTHING_SEEN), key);
        }
    }

    @Test
    @DisplayName("a key seen on a real button previews as a button, whatever it is called")
    void seenOnButton() {
        Predicate<String> seen = Set.of("gui.dungeontrain.support.donate", "chat.dungeontrain.open")::contains;
        assertEquals(TranslationPreviewKind.BUTTON,
            TranslationPreviewKind.of(lang("gui.dungeontrain.support.donate"), seen));
        // What the game did with the key beats what its name suggests.
        assertEquals(TranslationPreviewKind.BUTTON,
            TranslationPreviewKind.of(lang("chat.dungeontrain.open"), seen));
    }

    @Test
    @DisplayName("button-ish key endings preview as a button before the screen has been seen")
    void buttonSuffixes() {
        for (String key : new String[] {"gui.dungeontrain.translate.edit.save",
            "gui.dungeontrain.options.close", "gui.dungeontrain.videos.button"}) {
            assertEquals(TranslationPreviewKind.BUTTON, TranslationPreviewKind.of(lang(key), NOTHING_SEEN), key);
        }
    }

    @Test
    @DisplayName("anything else gets no Preview button rather than a misleading one")
    void none() {
        for (String key : new String[] {"gui.dungeontrain.translate.edit.hint",
            "advancements.dungeontrain.root.description", "gui.dungeontrain.support.title",
            "block.dungeontrain.track", ""}) {
            assertEquals(TranslationPreviewKind.NONE, TranslationPreviewKind.of(lang(key), NOTHING_SEEN), key);
        }
        assertEquals(TranslationPreviewKind.NONE, TranslationPreviewKind.of(null, NOTHING_SEEN));
    }

    @Test
    @DisplayName("each kind's button key sits under the edit screen's preview namespace")
    void buttonKeys() {
        assertEquals("gui.dungeontrain.translate.edit.preview.chat", TranslationPreviewKind.CHAT.buttonKey());
        assertEquals("gui.dungeontrain.translate.edit.preview.button", TranslationPreviewKind.BUTTON.buttonKey());
        assertEquals("gui.dungeontrain.translate.edit.preview.book", TranslationPreviewKind.BOOK.buttonKey());
    }
}
