package games.brennan.dungeontrain.client.localization.edit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Where a translated string ends up in the game, as far as the edit screen's Preview button is
 * concerned — a page of a book, a button label, a line of chat, or plain screen text.
 *
 * <p>Pure, so the guesswork is testable without a client. A string that matches none of the rules
 * gets {@link #TEXT} — screen text and tooltips, where most unplaced strings end up — so every
 * string has a preview; one read in several places can switch between them ({@link #viewsOf}).</p>
 */
public enum TranslationPreviewKind {
    BOOK,
    BUTTON,
    CHAT,
    TEXT,
    /** No string at all; never offered a preview. */
    NONE;

    /** Lang-key prefixes whose text is printed into chat — system messages, command feedback, deaths. */
    static final List<String> CHAT_PREFIXES =
        List.of("chat.", "commands.", "command.", "death.attack.");

    /**
     * Key endings that mean the string is a button's label, for buttons the player has not yet
     * looked at this install (see {@link ButtonKeyRecorder}, which is the stronger evidence).
     */
    static final List<String> BUTTON_SUFFIXES = List.of(
        ".button", ".save", ".close", ".reset", ".done", ".cancel", ".back", ".next", ".on", ".off");

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
     * Every place {@code unit} is known to be read, best evidence first: the preview opens on the
     * first and its switch cycles only these. {@link #TEXT} only when nothing places the string;
     * empty for no unit.
     *
     * @param seenOnButton whether a key has been seen labelling a real button ({@link ButtonKeyRecorder})
     * @param seenInChat   whether a key has been seen in a chat message ({@link ChatKeyRecorder})
     */
    public static List<TranslationPreviewKind> viewsOf(TranslationUnit unit, Predicate<String> seenOnButton,
                                                       Predicate<String> seenInChat) {
        if (unit == null) {
            return List.of();
        }
        if (unit.type() == TranslationUnit.Type.BOOK) {
            return List.of(BOOK);
        }
        String key = unit.id();
        if (key == null || key.isEmpty()) {
            return List.of(TEXT);
        }
        Set<TranslationPreviewKind> views = new LinkedHashSet<>();
        // Leaderboard lines are only ever printed in a leaderboard book.
        if (key.startsWith("book.") || key.startsWith(BookPreviewContext.LEADERBOARD_PREFIX)) {
            views.add(BOOK);
        }
        // What the game was seen doing with the key outranks inference from its name.
        if (seenOnButton != null && seenOnButton.test(key)) {
            views.add(BUTTON);
        }
        if ((seenInChat != null && seenInChat.test(key))
            || CHAT_PREFIXES.stream().anyMatch(key::startsWith)) {
            views.add(CHAT);
        }
        if (BUTTON_SUFFIXES.stream().anyMatch(key::endsWith)) {
            views.add(BUTTON);
        }
        if (views.isEmpty()) {
            views.add(TEXT);
        }
        return List.copyOf(views);
    }

    /** The lang key of the edit screen's Preview button for this kind; never called for NONE. */
    public String buttonKey() {
        return "gui.dungeontrain.translate.edit.preview." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
