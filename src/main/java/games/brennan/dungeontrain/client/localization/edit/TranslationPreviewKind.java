package games.brennan.dungeontrain.client.localization.edit;

import java.util.List;
import java.util.function.Predicate;

/**
 * Where a translated string ends up in the game, as far as the edit screen's Preview button is
 * concerned — a page of a book, a button label, or a line of chat.
 *
 * <p>Pure, so the guesswork is testable without a client. It only ever answers when it has a
 * reason to: a string that matches none of the rules gets {@link #NONE} and no Preview button,
 * because a preview in the wrong context would tell the translator something false.</p>
 */
public enum TranslationPreviewKind {
    BOOK,
    BUTTON,
    CHAT,
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
            return NONE;
        }
        if (key.startsWith("book.")) {
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
        return NONE;
    }

    /** The lang key of the edit screen's Preview button for this kind; never called for NONE. */
    public String buttonKey() {
        return "gui.dungeontrain.translate.edit.preview." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
