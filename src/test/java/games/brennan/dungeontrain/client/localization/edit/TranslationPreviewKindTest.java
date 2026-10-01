package games.brennan.dungeontrain.client.localization.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    @DisplayName("printed book prose and the stat/leaderboard book keys preview as a book")
    void books() {
        assertEquals(TranslationPreviewKind.BOOK,
            TranslationPreviewKind.of(book("random_books/deathnote#variants.0"), NOTHING_SEEN));
        assertEquals(TranslationPreviewKind.BOOK,
            TranslationPreviewKind.of(lang("book.dungeontrain.statbook.open.0"), NOTHING_SEEN));
        assertEquals(TranslationPreviewKind.BOOK,
            TranslationPreviewKind.of(lang("dungeontrain.leaderboard.you_unranked"), NOTHING_SEEN));
    }

    @Test
    @DisplayName("death lore and story notes are not books, so they get no book preview")
    void notBooks() {
        assertEquals(TranslationPreviewKind.NONE,
            TranslationPreviewKind.of(book("death_lore/default#4.narration"), NOTHING_SEEN));
        assertEquals(TranslationPreviewKind.NONE,
            TranslationPreviewKind.of(book("stories/x#letters.0.notes.0.text"), NOTHING_SEEN));
    }

    @Test
    @DisplayName("death messages are always chat; other chat needs to have been seen there")
    void chat() {
        assertEquals(TranslationPreviewKind.CHAT,
            TranslationPreviewKind.of(lang("death.attack.dungeontrain.train"), NOTHING_SEEN));
        Predicate<String> inChat = Set.of("chat.dungeontrain.welcome")::contains;
        assertEquals(List.of(TranslationPreviewKind.CHAT),
            TranslationPreviewKind.viewsOf(lang("chat.dungeontrain.welcome"), NOTHING_SEEN, inChat));
    }

    @Test
    @DisplayName("a key seen on a real button previews as a button, whatever it is called")
    void seenOnButton() {
        Predicate<String> seen = Set.of("gui.dungeontrain.support.donate", "chat.dungeontrain.open")::contains;
        assertEquals(TranslationPreviewKind.BUTTON,
            TranslationPreviewKind.of(lang("gui.dungeontrain.support.donate"), seen));
        assertEquals(TranslationPreviewKind.BUTTON,
            TranslationPreviewKind.of(lang("chat.dungeontrain.open"), seen));
    }

    @Test
    @DisplayName("a guess from the key's name is never a preview")
    void guessesGetNothing() {
        for (String key : new String[] {"gui.dungeontrain.translate.edit.save", "gui.dungeontrain.options.close",
            "chat.dungeontrain.welcome", "commands.dungeontrain.done", "gui.dungeontrain.support.title",
            "advancements.dungeontrain.root.description", ""}) {
            assertEquals(TranslationPreviewKind.NONE, TranslationPreviewKind.of(lang(key), NOTHING_SEEN), key);
        }
        assertEquals(TranslationPreviewKind.NONE, TranslationPreviewKind.of(null, NOTHING_SEEN));
    }

    @Test
    @DisplayName("a string read in several places offers each, and only those")
    void views() {
        Predicate<String> onButton = Set.of("chat.dungeontrain.open")::contains;
        Predicate<String> inChat = Set.of("chat.dungeontrain.open")::contains;
        assertEquals(List.of(TranslationPreviewKind.BUTTON, TranslationPreviewKind.CHAT),
            TranslationPreviewKind.viewsOf(lang("chat.dungeontrain.open"), onButton, inChat));
        assertEquals(List.of(TranslationPreviewKind.BOOK),
            TranslationPreviewKind.viewsOf(book("random_books/deathnote#title"), onButton, inChat));
    }

    @Test
    @DisplayName("each kind's button key sits under the edit screen's preview namespace")
    void buttonKeys() {
        assertEquals("gui.dungeontrain.translate.edit.preview.chat", TranslationPreviewKind.CHAT.buttonKey());
        assertEquals("gui.dungeontrain.translate.edit.preview.button", TranslationPreviewKind.BUTTON.buttonKey());
        assertEquals("gui.dungeontrain.translate.edit.preview.book", TranslationPreviewKind.BOOK.buttonKey());
    }
}
