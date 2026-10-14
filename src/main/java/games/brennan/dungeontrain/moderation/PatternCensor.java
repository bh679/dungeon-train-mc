package games.brennan.dungeontrain.moderation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The second pass of the Live Eye text filter: links and phone numbers. Pure, regex, no network.
 *
 * <p>A URL, a bare domain with a known top-level domain, a Discord invite, an IPv4 address or a
 * phone number in one line of player text becomes {@value TextCensor#MASK}. Unlike the word pass this
 * is never a strike — a link on stream is a doxxing or spam risk, not hate speech — so the caller
 * only warns. Same scope as the word pass (only while someone is live).</p>
 *
 * <p>Designed around the false positives that matter in this game: version strings ("v0.1286.0",
 * "1.21.1"), coordinates ("x 1234 z -567"), times ("12:30"), dates ("2026-10-14") and "e.g." all
 * stay. A bare domain needs a letter before the dot and a TLD from {@link #TLDS}; a phone number
 * needs 7–15 digits, grouped by at most spaces, dots, dashes or parentheses, and is never a date.</p>
 */
public final class PatternCensor {

    /** What matched, for the log line. */
    public enum Kind { LINK, PHONE }

    private static final String TLDS = "com|net|org|io|gg|tv|co|me|uk|de|fr|es|it|nl|ru|jp|cn|br|au|ca|nz|xyz|info|app|dev|live|link|gl|ly|be|us|eu|ch|se|pl|in|kr|tw|mx|pt|ro|id|my|th|vn|ph|ua|site|online|store|shop|club|zip|mov";

    /** Scheme or www. form: anything up to whitespace. */
    private static final Pattern URL = Pattern.compile("(?i)(?<![\\p{L}\\p{N}])(?:https?://|www\\.)[^\\s<>\"']+");
    /** Bare domain: letters before the dot, a known TLD, optional path. */
    private static final Pattern DOMAIN = Pattern.compile("(?i)(?<![\\p{L}\\p{N}.@/-])(?:[a-z0-9-]*[a-z][a-z0-9-]*\\.)+(?:" + TLDS + ")(?:/[^\\s<>\"']*)?(?![\\p{L}\\p{N}-])");
    /** Dotted IPv4, with or without port / path. */
    private static final Pattern IPV4 = Pattern.compile("(?<![\\p{L}\\p{N}.])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?(?:/[^\\s]*)?(?![\\p{L}\\p{N}.])");
    /** Candidate phone run: digits grouped by spaces, dots, dashes or parentheses, optional leading +. */
    private static final Pattern PHONE = Pattern.compile("(?<![\\p{L}\\p{N}+])\\+?\\(?\\d(?:[\\d\\s().-]*\\d)?(?![\\p{L}\\p{N}])");
    private static final Pattern DATE = Pattern.compile("^\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}$|^\\d{1,2}[-./]\\d{1,2}[-./]\\d{2,4}$");

    static final int MIN_PHONE_DIGITS = 7;
    static final int MAX_PHONE_DIGITS = 15;
    static final int MAX_PHONE_GROUPS = 5;

    private PatternCensor() {}

    /** Censor a finished line. */
    public static TextCensor.Result censor(String raw) {
        return run(raw, false);
    }

    /** Censor text still being typed: a match at the very end of the text is left alone until a boundary follows. */
    public static TextCensor.Result censorTail(String raw) {
        return run(raw, true);
    }

    private static TextCensor.Result run(String raw, boolean tail) {
        if (raw == null || raw.isEmpty() || raw.length() > TextCensor.MAX_LEN) return TextCensor.Result.unchanged(raw == null ? "" : raw);
        boolean[] mask = new boolean[raw.length()];
        List<String> hits = new ArrayList<>(2);
        for (Pattern p : new Pattern[] { URL, DOMAIN, IPV4 }) {
            Matcher m = p.matcher(raw);
            while (m.find()) {
                if (tail && m.end() == raw.length()) continue;
                fill(mask, m.start(), trimTrailingPunct(raw, m.start(), m.end()));
                hits.add(Kind.LINK.name());
            }
        }
        Matcher m = PHONE.matcher(raw);
        while (m.find()) {
            String s = m.group();
            if (tail && m.end() == raw.length()) continue;
            if (!looksLikePhone(s)) continue;
            fill(mask, m.start(), m.end());
            hits.add(Kind.PHONE.name());
        }
        if (hits.isEmpty()) return TextCensor.Result.unchanged(raw);
        return new TextCensor.Result(TextCensor.apply(raw, mask), List.copyOf(hits), true);
    }

    /** 7–15 digits in at most five groups, not a date, not a bare year-ish number inside a sentence. */
    static boolean looksLikePhone(String s) {
        String t = s.strip();
        if (DATE.matcher(t).matches()) return false;
        int digits = 0;
        int groups = 1;
        boolean prevDigit = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isDigit(c)) {
                digits++;
                prevDigit = true;
            } else if (prevDigit) {
                groups++;
                prevDigit = false;
            }
        }
        if (digits < MIN_PHONE_DIGITS || digits > MAX_PHONE_DIGITS || groups > MAX_PHONE_GROUPS) return false;
        // Two groups of 4 and 3 with a dash is what a coordinate pair or a score looks like far more
        // often than a phone number; a real number has a + / ( / 3+ groups / an unbroken run of 7+.
        if (groups == 2 && !t.startsWith("+") && !t.startsWith("(")) {
            String[] parts = t.split("[^\\d]+");
            return parts.length == 2 && (parts[0].length() >= 7 || parts[1].length() >= 7);
        }
        return true;
    }

    /** A link match keeps trailing sentence punctuation outside the mask ("see example.com." → "see ****."). */
    private static int trimTrailingPunct(String raw, int start, int end) {
        while (end > start + 1 && ".,;:!?)]}'\"".indexOf(raw.charAt(end - 1)) >= 0) end--;
        return end;
    }

    private static void fill(boolean[] mask, int from, int to) {
        for (int i = Math.max(0, from); i < Math.min(mask.length, to); i++) mask[i] = true;
    }
}
