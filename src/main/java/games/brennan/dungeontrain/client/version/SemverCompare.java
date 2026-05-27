package games.brennan.dungeontrain.client.version;

/**
 * Release-track comparator for the GitHub version checker.
 * <strong>Compares only the major and minor SemVer segments</strong> —
 * patch-level differences and pre-release suffixes are deliberately
 * ignored. This keeps the auto-release patch cascade (which ticks PATCH
 * up to ~22 times between real releases) from spamming users with
 * "Update available" notifications.
 *
 * <p>Strips an optional leading {@code v}/{@code V}, then compares the
 * first two dot-segments numerically. Missing segments are treated as
 * zero, so {@code "0.240"} equals {@code "0.240.0"} equals {@code "0.240.5"}.
 * Non-numeric input returns {@code 0} — the checker uses that as
 * "treat as equal" rather than erroring, so a server response with an
 * unexpected tag format degrades to "up to date" instead of spamming
 * a false update notification.</p>
 */
public final class SemverCompare {

    private SemverCompare() {}

    public static String stripV(String tag) {
        if (tag == null || tag.isEmpty()) return "";
        char c = tag.charAt(0);
        return (c == 'v' || c == 'V') ? tag.substring(1) : tag;
    }

    public static int compare(String a, String b) {
        String aCore = coreOf(stripV(a == null ? "" : a));
        String bCore = coreOf(stripV(b == null ? "" : b));

        String[] aParts = aCore.split("\\.");
        String[] bParts = bCore.split("\\.");
        for (int i = 0; i < SEGMENTS_TO_COMPARE; i++) {
            int av = parseSegment(i < aParts.length ? aParts[i] : "0");
            int bv = parseSegment(i < bParts.length ? bParts[i] : "0");
            if (av == FAIL || bv == FAIL) return 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static final int SEGMENTS_TO_COMPARE = 2;

    private static String coreOf(String s) {
        int dash = s.indexOf('-');
        return dash < 0 ? s : s.substring(0, dash);
    }

    private static final int FAIL = Integer.MIN_VALUE;

    private static int parseSegment(String s) {
        if (s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return FAIL;
        }
    }
}
