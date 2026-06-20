package games.brennan.dungeontrain.discord;

import games.brennan.dungeontrain.net.DeathNarrative;

import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * Builds the redesigned <b>top-level</b> (public / dev-channel) death report — "the manifest" — from
 * the per-death {@link DeathNarrative} the server rolled plus the run/lifetime stats. It folds the
 * in-game death screens into one Discord message:
 *
 * <ul>
 *   <li><b>title</b> = the fall-page narration the player saw (e.g. "Carriage 47. The dark had been
 *       waiting there all along…"), falling back to the death cause then "💀 name — Run Ended";</li>
 *   <li>each section is <b>headed by that page's narration</b> (deeds / cargo / lives), followed by a
 *       de-duped stat strip carrying only the numbers the prose doesn't already state;</li>
 *   <li>the platform epitaph closes it as an italic line.</li>
 * </ul>
 *
 * <p>Pure + side-effect-free (no Minecraft types) so it is unit-testable. Reuses {@link DeathReportFormat}
 * for distance/time/damage, and strips the U+0001/U+0002 number-colour sentinels the death screen wraps
 * around figures (the narration's numbers are English words, e.g. "eighty-three", which read fine here).</p>
 */
public final class DeathManifestFormat {

    private DeathManifestFormat() {}

    private static final String E_CAUSE = "🪦";
    private static final String E_DIST = "📏";
    private static final String E_TIME = "⏱️";
    private static final String E_DMG = "⚔️";
    private static final String E_LOOT = "🎒";
    private static final String E_BOOK = "📖";
    private static final String E_ADV = "🏆";
    private static final String E_CARR = "🚃";
    private static final String E_FRIEND = "🫂";
    private static final String E_BOOKS = "📚";

    /** The embed title: "{player} - Carriage {n}" — a compact header above the narrated body. */
    public static String title(String playerName, int carriage) {
        return playerName + " - Carriage " + carriage;
    }

    /**
     * The embed body: the fall narration as opening prose, then its stat strip, then a narration-headed
     * section per page with a de-duped stat strip, then the epitaph. Sections whose narration is empty
     * are omitted; the advancement segment is omitted when none were earned.
     */
    public static String description(DeathNarrative narr, String deathCause,
            double distanceBlocks, long runTicks, double damageDealt, double damageTaken,
            int loot, int booksRead, List<String> advancementTitles,
            long lifeCarriages, double lifeDistance, long lifeFriends, long lifeBooks) {
        StringBuilder sb = new StringBuilder();

        // the fall — narration as opening prose, then the strip.
        String fall = clean(narr == null ? "" : narr.fallNarration());
        if (!fall.isBlank()) sb.append(fall).append("\n\n");
        String cause = clean(deathCause);
        if (!cause.isBlank()) sb.append(E_CAUSE).append(' ').append(cause).append(" · ");
        sb.append(E_DIST).append(' ').append(DeathReportFormat.distance(distanceBlocks))
          .append(" · ").append(E_TIME).append(' ').append(DeathReportFormat.time(runTicks));

        // the deeds — narration carries mobs/met/slain/friends/hearts; strip adds the damage.
        String deeds = clean(narr == null ? "" : narr.deedsNarration());
        if (!deeds.isBlank()) {
            sb.append("\n\n").append(deeds)
              .append("\n\n").append(E_DMG).append(' ').append(DeathReportFormat.damage(damageDealt))
              .append(" dealt · ").append(DeathReportFormat.damage(damageTaken)).append(" taken");
        }

        // the cargo — strip adds loot / books / advancement names.
        String gear = clean(narr == null ? "" : narr.gearNarration());
        if (!gear.isBlank()) {
            sb.append("\n\n").append(gear).append("\n\n")
              .append(E_LOOT).append(' ').append(loot).append(" loot · ")
              .append(E_BOOK).append(' ').append(booksRead).append(" books");
            if (advancementTitles != null && !advancementTitles.isEmpty()) {
                sb.append(" · ").append(E_ADV).append(' ').append(String.join(", ", advancementTitles));
            }
        }

        // all your lives — narration carries the death count; strip adds the lifetime totals.
        String lives = clean(narr == null ? "" : narr.livesNarration());
        if (!lives.isBlank()) {
            sb.append("\n\n").append(lives).append("\n\n")
              .append(E_CARR).append(' ').append(lifeCarriages).append(" carriages · ")
              .append(E_DIST).append(' ').append(DeathReportFormat.distance(lifeDistance)).append(" · ")
              .append(E_FRIEND).append(' ').append(lifeFriends).append(" friends · ")
              .append(E_BOOKS).append(' ').append(lifeBooks).append(" books");
        }

        // the platform — the epitaph, italic.
        String epitaph = clean(narr == null ? "" : narr.platformEpitaph());
        if (!epitaph.isBlank()) sb.append("\n\n*").append(epitaph).append('*');

        return sb.toString();
    }

    /** Strip the number sentinels, then rewrite the second-person screen voice into the third person. */
    static String clean(String s) {
        return thirdPerson(strip(s));
    }

    // The death screen addresses the dead player directly ("you"); the public report is ABOUT them, so
    // rewrite to the third person. English second-person and third-person-plural share verb forms ("you
    // were" / "they were"), so most of the work is choosing they (subject) vs them (object) vs their.
    private static final int CI = Pattern.CASE_INSENSITIVE;
    // A modal before "you" keeps it subject even at a clause end ("so should you" → "so should they").
    private static final Pattern MODAL_YOU =
            Pattern.compile("(\\b(?:should|would|could|will|shall|may|might|must)\\s+)(you)\\b", CI);
    // "you" as an object: after a preposition / transitive verb ("for you", "the dark took you").
    private static final Pattern OBJ_PRE = Pattern.compile(
            "(\\b(?:for|from|of|to|with|at|on|by|against|into|onto|upon|like|than"
            + "|had|took|taken|reached|keep|keeps|kept|let|lets|gave|give|gives|told|brought|showed|sent)\\s+)(you)\\b", CI);
    // "you" as an object: at a clause end or before a conjunction ("reached you.", "behind you and").
    private static final Pattern OBJ_POST =
            Pattern.compile("\\b(you)\\b(?=\\s*(?:[.,;:!?)—]|and\\b|or\\b|but\\b|$))", CI);
    private static final Pattern SUBJ_YOU = Pattern.compile("\\b(you)\\b", CI);

    /** Rewrite second-person ("you/your/yours/yourself") to third-person ("they/them/their/…"). */
    static String thirdPerson(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        s = word(s, "yourselves", "themselves");
        s = word(s, "yourself", "themselves");
        s = word(s, "you're", "they're");
        s = word(s, "yours", "theirs");
        s = word(s, "your", "their");
        s = MODAL_YOU.matcher(s).replaceAll(m -> m.group(1) + matchCase("they", m.group(2)));
        s = OBJ_PRE.matcher(s).replaceAll(m -> m.group(1) + matchCase("them", m.group(2)));
        s = OBJ_POST.matcher(s).replaceAll(m -> matchCase("them", m.group(1)));
        s = SUBJ_YOU.matcher(s).replaceAll(m -> matchCase("they", m.group(1)));
        return s;
    }

    /** Whole-word, case-insensitive replace that preserves the first-letter case of each match. */
    private static String word(String text, String from, String to) {
        return Pattern.compile("\\b" + from + "\\b", CI).matcher(text)
                .replaceAll((MatchResult m) -> matchCase(to, m.group()));
    }

    /** {@code repl}, capitalised when {@code matched} begins with an uppercase letter. */
    private static String matchCase(String repl, String matched) {
        return (!matched.isEmpty() && Character.isUpperCase(matched.charAt(0)))
                ? Character.toUpperCase(repl.charAt(0)) + repl.substring(1)
                : repl;
    }

    /** Remove the U+0001/U+0002 number-colour sentinels the death screen uses, and trim. */
    static String strip(String s) {
        if (s == null) return "";
        return s.replace("", "").replace("", "").trim();
    }
}
