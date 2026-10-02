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

    /** Evidence that knows only what each set says. */
    private static PreviewEvidence seen(Set<String> onButton, Set<String> inChat, Set<String> inActionBar,
                                        Set<String> inTooltip, Set<String> items, Set<String> toasts) {
        return new PreviewEvidence() {
            @Override public boolean seenOnButton(String key) { return onButton.contains(key); }
            @Override public boolean seenInChat(String key) { return inChat.contains(key); }
            @Override public boolean seenInActionBar(String key) { return inActionBar.contains(key); }
            @Override public boolean seenInWidgetTooltip(String key) { return inTooltip.contains(key); }
            @Override public boolean isItemName(String key) { return items.contains(key); }
            @Override public boolean showsAdvancementToast(String key) { return toasts.contains(key); }
        };
    }

    private static List<TranslationPreviewKind> views(TranslationUnit unit, PreviewEvidence evidence) {
        return TranslationPreviewKind.viewsOf(unit, evidence);
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
        // Story notes are never shown.
        assertEquals(TranslationPreviewKind.NONE,
            TranslationPreviewKind.of(book("stories/x#letters.0.notes.0.text"), NOTHING_SEEN));
    }

    @Test
    @DisplayName("death lore lines and death messages preview on the death screen")
    void deathScreen() {
        assertEquals(List.of(TranslationPreviewKind.DEATH_SCREEN),
            views(book("death_lore/default#4.narration"), PreviewEvidence.NONE));
        // A death message leads the death screen and is always printed into chat.
        assertEquals(List.of(TranslationPreviewKind.DEATH_SCREEN, TranslationPreviewKind.CHAT),
            views(lang("death.attack.dungeontrain.abandoned"), PreviewEvidence.NONE));
    }

    @Test
    @DisplayName("item names, advancement toasts, widget tooltips and the action bar need their evidence")
    void scenes() {
        PreviewEvidence known = seen(Set.of(), Set.of(), Set.of("chat.dungeontrain.bar"),
            Set.of("gui.dungeontrain.tip"), Set.of("block.dungeontrain.track"),
            Set.of("advancements.dungeontrain.root.title"));
        assertEquals(List.of(TranslationPreviewKind.ITEM), views(lang("block.dungeontrain.track"), known));
        assertEquals(List.of(TranslationPreviewKind.ADVANCEMENT),
            views(lang("advancements.dungeontrain.root.title"), known));
        assertEquals(List.of(TranslationPreviewKind.TOOLTIP), views(lang("gui.dungeontrain.tip"), known));
        // Seen on the action bar only: never offered as chat.
        assertEquals(List.of(TranslationPreviewKind.ACTION_BAR), views(lang("chat.dungeontrain.bar"), known));
        assertEquals(List.of(), views(lang("block.dungeontrain.track"), PreviewEvidence.NONE));
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
    void several() {
        PreviewEvidence known = seen(Set.of("chat.dungeontrain.open"), Set.of("chat.dungeontrain.open"),
            Set.of(), Set.of(), Set.of(), Set.of());
        assertEquals(List.of(TranslationPreviewKind.BUTTON, TranslationPreviewKind.CHAT),
            views(lang("chat.dungeontrain.open"), known));
        assertEquals(List.of(TranslationPreviewKind.BOOK), views(book("random_books/deathnote#title"), known));
    }

    @Test
    @DisplayName("each kind's button key sits under the edit screen's preview namespace")
    void buttonKeys() {
        assertEquals("gui.dungeontrain.translate.edit.preview.chat", TranslationPreviewKind.CHAT.buttonKey());
        assertEquals("gui.dungeontrain.translate.edit.preview.button", TranslationPreviewKind.BUTTON.buttonKey());
        assertEquals("gui.dungeontrain.translate.edit.preview.book", TranslationPreviewKind.BOOK.buttonKey());
    }
}
