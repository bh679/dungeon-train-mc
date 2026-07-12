package games.brennan.dungeontrain.narrative;

import java.util.List;
import java.util.Locale;

/**
 * Pure helpers for the "Death Note" curse book: recognising the trigger title and extracting the
 * target player from the pages. Kept dependency-free (no Minecraft types) so the signing rules can be
 * unit-tested without a running server.
 */
public final class DeathNoteTitle {

    private DeathNoteTitle() {}

    /**
     * True when {@code title} names a Death Note — matched case-insensitively and ignoring
     * whitespace, so "Death Note", "DEATHNOTE", "death note" and "  death  note " all qualify.
     */
    public static boolean isDeathNoteTitle(String title) {
        if (title == null) return false;
        String normalized = title.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return normalized.equals("deathnote");
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
