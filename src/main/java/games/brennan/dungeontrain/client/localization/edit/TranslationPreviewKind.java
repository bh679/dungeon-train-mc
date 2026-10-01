package games.brennan.dungeontrain.client.localization.edit;

import java.util.List;
import java.util.function.Predicate;

/**
 * Where a translated string ends up in the game, as far as the edit screen's Preview button is
 * concerned — a page of a book, a button label, a line of chat, or plain screen text.
 *
 * <p>Pure, so the guesswork is testable without a client. A string that matches none of the rules
 * gets {@link #TEXT} — screen text and tooltips, where most unplaced strings end up — and the
 * preview can switch to any other view ({@link #next}), so every string has one.</p>
 */
public enum TranslationPreviewKind {
    BOOK,
    BUTTON,
    CHAT,
    TEXT,
    /** No string at all; never offered a preview. */
    NONE;

    /** The views the preview cycles through, in order. */
    private static final List<TranslationPreviewKind> VIEWS = List.of(TEXT, BOOK, BUTTON, CHAT);

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
     * Which preview fits {@code unit}.
     *
     * @param seenOnButton whether a key has been seen labelling a real button on some screen
     */
    public static TranslationPreviewKind of(TranslationUnit unit, Predicate<String> seenOnButton) {
        if (unit == null) {
            return NONE;
        }
        if (unit.type() == TranslationUnit.Type.BOOK) {
            return BOOK;
        }
        String key = unit.id();
        if (key == null || key.isEmpty()) {
            return TEXT;
        }
        // Leaderboard lines are only ever printed in a leaderboard book.
        if (key.startsWith("book.") || key.startsWith(BookPreviewContext.LEADERBOARD_PREFIX)) {
            return BOOK;
        }
        // Seen on a button outranks the prefixes: it is what the game actually did with the key,
        // where the rest is inference from its name.
        if (seenOnButton != null && seenOnButton.test(key)) {
            return BUTTON;
        }
        for (String prefix : CHAT_PREFIXES) {
            if (key.startsWith(prefix)) {
                return CHAT;
            }
        }
        for (String suffix : BUTTON_SUFFIXES) {
            if (key.endsWith(suffix)) {
                return BUTTON;
            }
        }
        return TEXT;
    }

    /** The view after this one, for the preview's switch button. */
    public TranslationPreviewKind next() {
        int at = VIEWS.indexOf(this);
        return VIEWS.get((at + 1) % VIEWS.size());
    }

    /** The lang key of the edit screen's Preview button for this kind; never called for NONE. */
    public String buttonKey() {
        return "gui.dungeontrain.translate.edit.preview." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
