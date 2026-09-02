package games.brennan.dungeontrain.cheat;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Shared mod-ID hygiene for the two Free Play mod lists — {@link CheatModList} (the blacklist) and
 * {@link ApprovedModList} (the whitelist).
 *
 * <p>Both take IDs from the relay, and both feed a match against the installed mod list, so both
 * need exactly the same answer to "is this a plausible mod ID". Keeping one copy is not tidiness:
 * if the two rules ever drifted, an ID the whitelist rejected but the blacklist accepted (or the
 * other way round) would change a player's run for a reason nobody could read off either file.</p>
 *
 * <p>Validation is deliberately strict and silent. A malformed value is DROPPED rather than
 * repaired, because both directions of a bad ID cost something real: a typo in the blacklist can
 * false-positive an innocent mod, and a typo in the whitelist un-approves one.</p>
 */
public final class ModIds {

    /** Longest plausible mod ID. Real ones are far shorter; this only bounds pathological input. */
    public static final int MAX_ID_LEN = 64;

    private ModIds() {}

    /**
     * A plausible mod ID: non-empty after trim, sane length, only {@code [a-z0-9_.-]} (after
     * lowercasing). Mirrors the relay's {@code isValidModId} in cheatmods.js / approvedmods.js.
     */
    public static boolean isValid(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > MAX_ID_LEN) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.';
            if (!ok) return false;
        }
        return true;
    }

    /** Lowercase copy of {@code raw} holding only the entries that pass {@link #isValid}. */
    public static Set<String> sanitize(Collection<String> raw, int maxIds) {
        if (raw == null || raw.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>();
        for (String id : raw) {
            if (isValid(id)) out.add(id.trim().toLowerCase(Locale.ROOT));
            if (out.size() >= maxIds) break;
        }
        return Set.copyOf(out);
    }

    /** Lowercase, or empty for a null/blank input. The one place IDs are normalised for matching. */
    public static String normalise(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }
}
