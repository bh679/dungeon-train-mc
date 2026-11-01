package games.brennan.dungeontrain.client.moty;

import java.time.LocalDate;
import java.util.Optional;

/**
 * CurseForge's Minecraft Mod of the Year 2026, as far as Dungeon Train asks players about it: which
 * phase the contest is in on a given day, and what (if anything) to ask a player next.
 *
 * <p>Only warm players are asked — someone who has answered the "how likely are you to recommend"
 * survey question with {@link #RECOMMEND_THRESHOLD} or more at least once. While nominations are open
 * they are asked to nominate the Dungeon Train <b>modpack</b> for Modpack of the Year, and once that
 * is done, the Dungeon Train <b>mod</b> for Mod of the Year. While voting is open they are asked to
 * vote for whichever of the two the jury made a finalist ({@link #MODPACK_IS_FINALIST},
 * {@link #MOD_IS_FINALIST} — flipped by a release once the finalists are announced). Each ask repeats
 * until the player says it is done.</p>
 *
 * <p>Pure — no Minecraft types — so the dates and the order are pinned by {@code ModOfTheYearTest}.</p>
 */
public final class ModOfTheYear {

    /** What the contest is asking for today. */
    public enum Phase { NONE, NOMINATE, VOTE }

    /** One thing to ask a player, in the order they are asked. */
    public enum Ask {
        NOMINATE_MODPACK(true, false),
        NOMINATE_MOD(false, false),
        VOTE_MODPACK(true, true),
        VOTE_MOD(false, true);

        private final boolean modpack;
        private final boolean voting;

        Ask(boolean modpack, boolean voting) {
            this.modpack = modpack;
            this.voting = voting;
        }

        /** The Modpack of the Year category (else Mod of the Year). */
        public boolean modpack() {
            return modpack;
        }

        public boolean voting() {
            return voting;
        }

        /** Where the main button goes: the nomination form, or the contest page where voting happens. */
        public String contestUrl() {
            return voting ? VOTE_URL : NOMINATION_URL;
        }

        /** The listing being nominated or voted for — copied so it can be pasted into the form. */
        public String projectUrl() {
            return modpack ? MODPACK_URL : MOD_URL;
        }
    }

    /** What the player has already done (or said they have). */
    public record Done(boolean modpackNominated, boolean modNominated, boolean modpackVoted, boolean modVoted) {}

    /** Lowest NPS answer (0-10) that counts as a recommendation. */
    public static final int RECOMMEND_THRESHOLD = 8;

    /**
     * Voting only lists the finalists the jury picks (9 modpacks, 15 mods), so each vote ask stays off
     * until Dungeon Train is one. Flip in a release once the finalists are announced.
     */
    public static final boolean MODPACK_IS_FINALIST = false;
    public static final boolean MOD_IS_FINALIST = false;

    // Contest calendar (both ends inclusive), from the event page.
    static final LocalDate NOMINATIONS_OPEN = LocalDate.of(2026, 10, 26);
    static final LocalDate NOMINATIONS_CLOSE = LocalDate.of(2026, 11, 9);
    static final LocalDate VOTING_OPEN = LocalDate.of(2026, 12, 7);
    static final LocalDate VOTING_CLOSE = LocalDate.of(2026, 12, 17);

    /** The contest's nomination form. */
    public static final String NOMINATION_URL = "https://links.curseforge.com/moty2026-nominations-lp";
    /** The contest page, where voting happens. */
    public static final String VOTE_URL = "https://mod.curseforge.com/minecraft-mod-of-the-year-2026/";
    public static final String MODPACK_URL = "https://www.curseforge.com/minecraft/modpacks/dungeon-train";
    public static final String MOD_URL = "https://www.curseforge.com/minecraft/mc-mods/dungeon-train";

    private ModOfTheYear() {}

    /** The phase on {@code today} (UTC), honouring the finalist flags. */
    public static Phase phaseOn(LocalDate today) {
        return phaseOn(today, MODPACK_IS_FINALIST || MOD_IS_FINALIST);
    }

    static Phase phaseOn(LocalDate today, boolean anyFinalist) {
        if (within(today, NOMINATIONS_OPEN, NOMINATIONS_CLOSE)) return Phase.NOMINATE;
        if (anyFinalist && within(today, VOTING_OPEN, VOTING_CLOSE)) return Phase.VOTE;
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

    /** The next thing to ask in {@code phase}, honouring the finalist flags; empty when there is nothing. */
    public static Optional<Ask> nextAsk(Phase phase, boolean qualifies, Done done) {
        return nextAsk(phase, qualifies, done, MODPACK_IS_FINALIST, MOD_IS_FINALIST);
    }

    static Optional<Ask> nextAsk(Phase phase, boolean qualifies, Done done,
                                 boolean modpackFinalist, boolean modFinalist) {
        if (!qualifies) return Optional.empty();
        return switch (phase) {
            case NOMINATE -> !done.modpackNominated() ? Optional.of(Ask.NOMINATE_MODPACK)
                    : !done.modNominated() ? Optional.of(Ask.NOMINATE_MOD)
                    : Optional.empty();
            case VOTE -> modpackFinalist && !done.modpackVoted() ? Optional.of(Ask.VOTE_MODPACK)
                    : modFinalist && !done.modVoted() ? Optional.of(Ask.VOTE_MOD)
                    : Optional.empty();
            case NONE -> Optional.empty();
        };
    }

    private static boolean within(LocalDate day, LocalDate first, LocalDate last) {
        return !day.isBefore(first) && !day.isAfter(last);
    }
}
