package games.brennan.dungeontrain.client.moty;

import java.time.LocalDate;

/**
 * CurseForge's Minecraft Mod of the Year 2026, as far as Dungeon Train asks players about it: which
 * phase the contest is in on a given day, and whether a player should be asked to take part.
 *
 * <p>Only warm players are asked — someone who has answered the "how likely are you to recommend"
 * survey question with {@link #RECOMMEND_THRESHOLD} or more at least once. They are asked to
 * nominate the Dungeon Train <b>modpack</b> while nominations are open, and to vote for it while
 * voting is open — the vote phase only once the jury has picked it as a finalist
 * ({@link #DT_IS_FINALIST}, flipped by a release if it is). Each phase is asked until the player
 * says they have done it.</p>
 *
 * <p>Pure — no Minecraft types — so the dates and gates are pinned by {@code ModOfTheYearTest}.</p>
 */
public final class ModOfTheYear {

    /** What the contest is asking for today. */
    public enum Phase { NONE, NOMINATE, VOTE }

    /** Lowest NPS answer (0-10) that counts as a recommendation. */
    public static final int RECOMMEND_THRESHOLD = 8;

    /**
     * Voting only lists the 24 finalists the jury picks, so the vote phase stays off until Dungeon
     * Train is one. Flip to {@code true} in a release once the finalists are announced.
     */
    public static final boolean DT_IS_FINALIST = false;

    // Contest calendar (both ends inclusive), from the event page.
    static final LocalDate NOMINATIONS_OPEN = LocalDate.of(2026, 10, 26);
    static final LocalDate NOMINATIONS_CLOSE = LocalDate.of(2026, 11, 9);
    static final LocalDate VOTING_OPEN = LocalDate.of(2026, 12, 7);
    static final LocalDate VOTING_CLOSE = LocalDate.of(2026, 12, 17);

    /** The contest's nomination form. */
    public static final String NOMINATION_URL = "https://links.curseforge.com/moty2026-nominations-lp";
    /** The contest page, where voting happens. */
    public static final String VOTE_URL = "https://mod.curseforge.com/minecraft-mod-of-the-year-2026/";
    /** What players nominate — copied to the clipboard so it can be pasted into the form. */
    public static final String MODPACK_URL = "https://www.curseforge.com/minecraft/modpacks/dungeon-train";

    private ModOfTheYear() {}

    /** The phase on {@code today} (UTC), honouring {@link #DT_IS_FINALIST}. */
    public static Phase phaseOn(LocalDate today) {
        return phaseOn(today, DT_IS_FINALIST);
    }

    static Phase phaseOn(LocalDate today, boolean finalist) {
        if (within(today, NOMINATIONS_OPEN, NOMINATIONS_CLOSE)) return Phase.NOMINATE;
        if (finalist && within(today, VOTING_OPEN, VOTING_CLOSE)) return Phase.VOTE;
        return Phase.NONE;
    }

    /** Whether an NPS answer counts as a recommendation. */
    public static boolean isRecommendation(int npsScore) {
        return npsScore >= RECOMMEND_THRESHOLD;
    }

    /**
     * Whether this player qualifies: the sticky "ever recommended" flag, or — for answers given
     * before that flag existed — a last NPS answer that was a recommendation.
     */
    public static boolean qualifies(boolean everRecommended, int lastNpsScore) {
        return everRecommended || isRecommendation(lastNpsScore);
    }

    /** Whether to ask in {@code phase}, given what the player has already done. */
    public static boolean shouldAsk(Phase phase, boolean qualifies, boolean nominated, boolean voted) {
        if (!qualifies) return false;
        return switch (phase) {
            case NOMINATE -> !nominated;
            case VOTE -> !voted;
            case NONE -> false;
        };
    }

    private static boolean within(LocalDate day, LocalDate first, LocalDate last) {
        return !day.isBefore(first) && !day.isAfter(last);
    }
}
