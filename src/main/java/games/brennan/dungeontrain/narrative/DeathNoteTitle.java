package games.brennan.dungeontrain.narrative;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Pure helpers for the "Death Note" curse book: recognising the trigger title and extracting the
 * target player from the pages. Kept dependency-free (no Minecraft types) so the signing rules can be
 * unit-tested without a running server.
 */
public final class DeathNoteTitle {

    /** The canonical English trigger, in normalized form — always accepted regardless of locale. */
    private static final String ENGLISH = "deathnote";

    private DeathNoteTitle() {}

    /**
     * True when {@code title} names a Death Note in English — matched case-insensitively and ignoring
     * whitespace, so "Death Note", "DEATHNOTE", "death note" and "  death  note " all qualify.
     */
    public static boolean isDeathNoteTitle(String title) {
        return isDeathNoteTitle(title, null);
    }

    /**
     * True when {@code title} names a Death Note — either the canonical English "death note" (always
     * accepted) or any of {@code localizedTitles}, the translated trigger words for the author's own
     * client locale (see {@link DeathNoteTitleLocalization}). All comparisons ignore case and
     * whitespace. A {@code null}/empty {@code localizedTitles} reduces to English-only matching.
     */
    public static boolean isDeathNoteTitle(String title, Collection<String> localizedTitles) {
        if (title == null) return false;
        String normalized = normalize(title);
        if (normalized.equals(ENGLISH)) return true;
        if (localizedTitles == null) return false;
        for (String candidate : localizedTitles) {
            if (candidate != null && normalize(candidate).equals(normalized)) return true;
        }
        return false;
    }

    /** Lowercase (locale-independent) and strip all whitespace — the shared title-matching form. */
    static String normalize(String title) {
        return title.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /**
     * The curse target: the first non-empty line of page 1, trimmed (the player writes the victim's
     * username on its own first line). Returns {@code null} when there is no page 1 or it is blank —
     * an empty Death Note simply targets no one. Case is preserved for display; matching against a
     * player is done case-insensitively downstream.
     */
    public static String firstLineTarget(List<String> pages) {
        if (pages == null || pages.isEmpty()) return null;
        String firstPage = pages.get(0);
        if (firstPage == null) return null;
        for (String line : firstPage.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) return trimmed;
        }
        return null;
    }
}
