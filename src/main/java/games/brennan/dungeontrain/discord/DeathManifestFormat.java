package games.brennan.dungeontrain.discord;

import games.brennan.discordpresence.discord.DeathField;
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
 *   <li><b>title</b> = "{player} - Carriage {n}";</li>
 *   <li><b>description</b> = the rolled narration as prose — the fall / deeds / cargo paragraphs,
 *       closed by the italic platform epitaph;</li>
 *   <li><b>fields</b> = three inline stat columns (The fall / This run / The souls) carrying the run's
 *       numbers, which Discord lays out side-by-side.</li>
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
    private static final String E_MET = "👥";
    private static final String E_BEFRIEND = "🤝";
    private static final String E_SLAIN = "🗡️";

    /** The embed title: "{player} - Carriage {n}" — a compact header above the narrated body. */
    public static String title(String playerName, int carriage) {
        return playerName + " - Carriage " + carriage;
    }

    /**
     * The embed body: the fall narration, then ONE optional middle paragraph, closed by the italic
     * platform epitaph. The middle paragraph is:
     * <ul>
     *   <li>the <b>deeds</b> (combat/social) line when the run had any social contact — at least one
     *       other soul slain or befriended;</li>
     *   <li>otherwise the <b>cargo</b> line, but only when something was looted ({@code loot > 0});</li>
     *   <li>otherwise nothing (just the fall + epitaph).</li>
     * </ul>
     * The lifetime "lives" narration is intentionally left out of the embed (this-run only); the numbers
     * move to {@link #fields}.
     */
    public static String description(DeathNarrative narr, int playersKilled, int playersBefriended, int loot) {
        StringBuilder sb = new StringBuilder();

        append(sb, clean(narr == null ? "" : narr.fallNarration()));

        // Deeds when the run had social contact; else the cargo line only if anything was looted.
        if (playersKilled + playersBefriended > 0) {
            append(sb, clean(narr == null ? "" : narr.deedsNarration()));
        } else if (loot > 0) {
            append(sb, clean(narr == null ? "" : narr.gearNarration()));
        }

        // the platform — the epitaph, italic.
        String epitaph = clean(narr == null ? "" : narr.platformEpitaph());
        if (!epitaph.isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append('*').append(epitaph).append('*');
        }

        return sb.toString();
    }

    /** Append a non-blank paragraph, separated from any preceding text by a blank line. */
    private static void append(StringBuilder sb, String paragraph) {
        if (paragraph.isBlank()) return;
        if (sb.length() > 0) sb.append("\n\n");
        sb.append(paragraph);
    }

    /**
     * The embed's three inline stat columns, which Discord renders side-by-side:
     * <ul>
     *   <li><b>The fall</b> — death cause / distance / run time;</li>
     *   <li><b>This run</b> — damage dealt &amp; taken / loot &amp; books / earned advancements
     *       (the advancement line is omitted when none were earned);</li>
     *   <li><b>The souls</b> — other players/PlayerMobs met / befriended / slain this run.</li>
     * </ul>
     * Each value is newline-separated; emoji are kept (Discord renders them).
     */
    public static List<DeathField> fields(String deathCause,
            double distanceBlocks, long runTicks, double damageDealt, double damageTaken,
            int loot, int booksRead, List<String> advancementTitles,
            int playersEncountered, int playersBefriended, int playersKilled) {

        // The fall — cause / distance / time.
        StringBuilder fall = new StringBuilder();
        String cause = clean(deathCause);
        if (!cause.isBlank()) fall.append(E_CAUSE).append(' ').append(cause).append('\n');
        fall.append(E_DIST).append(' ').append(DeathReportFormat.distance(distanceBlocks)).append('\n')
            .append(E_TIME).append(' ').append(DeathReportFormat.time(runTicks));

        // This run — damage / loot + books / advancements.
        StringBuilder run = new StringBuilder();
        run.append(E_DMG).append(' ').append(DeathReportFormat.damage(damageDealt)).append(" dealt · ")
           .append(DeathReportFormat.damage(damageTaken)).append(" taken").append('\n')
           .append(E_LOOT).append(' ').append(loot).append(" loot · ")
           .append(E_BOOK).append(' ').append(booksRead).append(" books");
        if (advancementTitles != null && !advancementTitles.isEmpty()) {
            run.append('\n').append(E_ADV).append(' ').append(String.join(", ", advancementTitles));
        }

        // The souls — other souls met / befriended / slain this run.
        String souls = E_MET + ' ' + playersEncountered + " met\n"
                + E_BEFRIEND + ' ' + playersBefriended + " befriended\n"
                + E_SLAIN + ' ' + playersKilled + " slain";

        return List.of(
                new DeathField("The fall", fall.toString()),
                new DeathField("This run", run.toString()),
                new DeathField("The souls", souls));
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
