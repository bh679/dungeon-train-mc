package games.brennan.dungeontrain.client.localization.edit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Where a translated string ends up in the game, as far as the edit screen's Preview button is
 * concerned — a book page, the death screen, an item tooltip, an advancement popup, a button, a
 * widget's tooltip, a chat line or the action bar.
 *
 * <p>Pure, so it is testable without a client. It only ever answers with certainty: a view is
 * offered when the code or data fixes where the string goes (book prose, the stat and leaderboard
 * books, death lore, death messages, item names, advancement titles) or when the game has been seen
 * putting it there ({@link PreviewEvidence}). A guess from a key's name gets no preview — a preview in
 * the wrong setting would tell the translator something false.</p>
 */
public enum TranslationPreviewKind {
    BOOK,
    DEATH_SCREEN,
    ITEM,
    ADVANCEMENT,
    BUTTON,
    TOOLTIP,
    CHAT,
    ACTION_BAR,
    /** Nowhere certain; no Preview button. */
    NONE;

    /** Death messages: vanilla always prints these into chat, and DT's death screen leads with them. */
    static final String DEATH_MESSAGE_PREFIX = "death.attack.";

    /** Death lore: the death screen's question/narration lines, never a book. */
    static final String DEATH_LORE_PATH = "death_lore/";
    private static final Pattern DEATH_LORE_LINE = Pattern.compile("\\d+\\.(question|subline|narration|epitaph)");

    /**
     * Which preview fits {@code unit} best — the first of {@link #viewsOf}, or NONE.
     *
     * @param seenOnButton whether a key has been seen labelling a real button on some screen
     */
    public static TranslationPreviewKind of(TranslationUnit unit, Predicate<String> seenOnButton) {
        List<TranslationPreviewKind> views = viewsOf(unit, new PreviewEvidence() {
            @Override
            public boolean seenOnButton(String key) {
                return seenOnButton != null && seenOnButton.test(key);
            }
        });
        return views.isEmpty() ? NONE : views.get(0);
    }

    /**
     * Every place {@code unit} is known to be read: the preview opens on the first and its switch
     * cycles only these. Empty when nothing places it for certain — then there is no preview.
     */
    public static List<TranslationPreviewKind> viewsOf(TranslationUnit unit, PreviewEvidence evidence) {
        if (unit == null || unit.id() == null || unit.id().isEmpty()) {
            return List.of();
        }
        PreviewEvidence known = evidence == null ? PreviewEvidence.NONE : evidence;
        if (unit.type() == TranslationUnit.Type.BOOK) {
            if (unit.bookPath().startsWith(DEATH_LORE_PATH)) {
                return DEATH_LORE_LINE.matcher(unit.bookField()).matches() ? List.of(DEATH_SCREEN) : List.of();
            }
            // Prose a book prints; story notes are never shown.
            return BookPreviewContext.sourceOf(unit) != BookPreviewContext.Source.NONE ? List.of(BOOK) : List.of();
        }
        String key = unit.id();
        Set<TranslationPreviewKind> views = new LinkedHashSet<>();
        // Only the stat book and leaderboard books print lang keys, and these are all of theirs.
        if (BookPreviewContext.sourceOf(unit) != BookPreviewContext.Source.NONE) {
            views.add(BOOK);
        }
        if (key.startsWith(DEATH_MESSAGE_PREFIX)) {
            views.add(DEATH_SCREEN);
        }
        if (known.isItemName(key) || known.seenInItemTooltip(key)) {
            views.add(ITEM);
        }
        if (known.showsAdvancementToast(key)) {
            views.add(ADVANCEMENT);
        }
        if (known.seenOnButton(key)) {
            views.add(BUTTON);
        }
        if (known.seenInWidgetTooltip(key)) {
            views.add(TOOLTIP);
        }
        if (key.startsWith(DEATH_MESSAGE_PREFIX) || known.seenInChat(key)) {
            views.add(CHAT);
        }
        if (known.seenInActionBar(key)) {
            views.add(ACTION_BAR);
        }
        return List.copyOf(views);
    }

    /** The lang key of the edit screen's Preview button for this kind; never called for NONE. */
    public String buttonKey() {
        return "gui.dungeontrain.translate.edit.preview." + name().toLowerCase(Locale.ROOT);
    }
}
