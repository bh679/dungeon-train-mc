package games.brennan.dungeontrain.perks.effects.archivist;

import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;

/**
 * The Archivist class's tier 2/3 perk ids (kebab-case slugs of the tree's perk names). Rank is read
 * through {@code Perks.rank(player, id)}: the points the player has made active this life, 0 when the
 * perk is locked or inactive.
 */
public final class ArchivistPerks {

    /** Tier 2: the Live Eye cannot be forcibly taken for longer ({@link LiveEyeSafeDuration}). */
    public static final String LIVE_EYE_SAFE_DURATION = "live-eye-safe-duration";
    /** Tier 3: takeover and book votes count for more ({@link VoteWeight}). */
    public static final String VOTE_WEIGHT = "vote-weight";
    /** Tier 3: disposable cameras take extra shots ({@link ExtraCamShots}). */
    public static final String EXTRA_CAM_SHOTS = "extra-cam-shots";

    /** The relay's default holder-vote window (LIVE_TAKEOVER_HOLDER_VOTE_MS), in minutes. */
    static final double HOLDER_VOTE_BASE_MIN = 10;
    /** The relay's default takeover protection (LIVE_TAKEOVER_MIN_MS) less the holder-vote window, in minutes. */
    static final double COMMUNITY_VOTE_BASE_MIN = 10;
    private static final double MS_PER_MIN = 60_000.0;

    private ArchivistPerks() {}

    /** Marks the built Archivist perks and registers their menu stats. */
    public static void declare() {
        BuiltPerks.markBuilt(LIVE_EYE_SAFE_DURATION, VOTE_WEIGHT, EXTRA_CAM_SHOTS);
        PerkStats.register(LIVE_EYE_SAFE_DURATION, "protected",
            r -> PerkStats.num(protectedMinutes(r), Unit.MINUTES));
        PerkStats.register(LIVE_EYE_SAFE_DURATION, "community_vote",
            r -> PerkStats.num(communityVoteMinutes(r), Unit.MINUTES));
        PerkStats.register(EXTRA_CAM_SHOTS, "extra_shots",
            r -> PerkStats.plus(ExtraCamShots.CHANCE_PER_RANK * r, Unit.PLAIN));
        PerkStats.register(VOTE_WEIGHT, "vote_weight",
            r -> PerkStats.num(VoteWeight.weight(r), Unit.MULTIPLIER));
    }

    /** Minutes the holder may vote on a takeover: the relay's window plus this perk's share. */
    static double protectedMinutes(int rank) {
        return HOLDER_VOTE_BASE_MIN + LiveEyeSafeDuration.secondWindowExtraMs(rank) / MS_PER_MIN;
    }

    /**
     * Minutes a takeover still needs a community vote once the holder's vote window has passed. Both
     * relay deadlines count from the start of the hold, so this is the takeover extra less the holder's.
     */
    static double communityVoteMinutes(int rank) {
        long extra = LiveEyeSafeDuration.firstWindowExtraMs(rank) - LiveEyeSafeDuration.secondWindowExtraMs(rank);
        return COMMUNITY_VOTE_BASE_MIN + extra / MS_PER_MIN;
    }
}
