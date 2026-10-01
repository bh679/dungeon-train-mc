package games.brennan.dungeontrain.client.localization.edit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Where a translated string ends up in the game, as far as the edit screen's Preview button is
 * concerned — a page of a book, a button label, or a line of chat.
 *
 * <p>Pure, so it is testable without a client. It only ever answers with certainty: a view is
 * offered when the code fixes where the string goes (book prose, the stat and leaderboard books,
 * vanilla death messages) or when the game has been seen putting it there ({@link ButtonKeyRecorder},
 * {@link ChatKeyRecorder}). A guess from a key's name gets no preview — a preview in the wrong
 * setting would tell the translator something false.</p>
 */
public enum TranslationPreviewKind {
    BOOK,
    BUTTON,
    CHAT,
    /** Nowhere certain; no Preview button. */
    NONE;

    /** Death messages: vanilla always prints these into chat. */
    static final String DEATH_MESSAGE_PREFIX = "death.attack.";

    /** Death lore is drawn on the death screen, not in a book; it has no preview of its own yet. */
    static final String DEATH_LORE_PATH = "death_lore/";

    /**
     * Which preview fits {@code unit} best — the first of {@link #viewsOf}, or NONE for no unit.
     *
     * @param seenOnButton whether a key has been seen labelling a real button on some screen
     */
    public static TranslationPreviewKind of(TranslationUnit unit, Predicate<String> seenOnButton) {
        List<TranslationPreviewKind> views = viewsOf(unit, seenOnButton, key -> false);
        return views.isEmpty() ? NONE : views.get(0);
    }

    /**
     * Every place {@code unit} is known to be read: the preview opens on the first and its switch
     * cycles only these. Empty when nothing places it for certain — then there is no preview.
     *
     * @param seenOnButton whether a key has been seen labelling a real button ({@link ButtonKeyRecorder})
     * @param seenInChat   whether a key has been seen in a chat message ({@link ChatKeyRecorder})
     */
    public static List<TranslationPreviewKind> viewsOf(TranslationUnit unit, Predicate<String> seenOnButton,
                                                       Predicate<String> seenInChat) {
        if (unit == null || unit.id() == null || unit.id().isEmpty()) {
            return List.of();
        }
        if (unit.type() == TranslationUnit.Type.BOOK) {
            // Prose a book prints; not death lore (the death screen) or story notes (never shown).
            boolean printed = !unit.bookPath().startsWith(DEATH_LORE_PATH)
                && BookPreviewContext.sourceOf(unit) != BookPreviewContext.Source.NONE;
            return printed ? List.of(BOOK) : List.of();
        }
        String key = unit.id();
        Set<TranslationPreviewKind> views = new LinkedHashSet<>();
        // Only the stat book and leaderboard books print lang keys, and these are all of theirs.
        if (BookPreviewContext.sourceOf(unit) != BookPreviewContext.Source.NONE) {
            views.add(BOOK);
        }
        if (seenOnButton != null && seenOnButton.test(key)) {
            views.add(BUTTON);
        }
        if (key.startsWith(DEATH_MESSAGE_PREFIX) || (seenInChat != null && seenInChat.test(key))) {
            views.add(CHAT);
        }
        return List.copyOf(views);
    }

    /** The lang key of the edit screen's Preview button for this kind; never called for NONE. */
    public String buttonKey() {
        return "gui.dungeontrain.translate.edit.preview." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
