package games.brennan.dungeontrain.perks.effects.archivist;

/**
 * The Archivist class's tier 2/3 perk ids (kebab-case slugs of the tree's perk names). Rank is read
 * through {@code Perks.rank(player, id)}: the points the player has made active this life, 0 when the
 * perk is locked or inactive.
 */
public final class ArchivistPerks {

    /** Tier 2: the Live Eye cannot be forcibly taken for longer ({@link LiveEyeSafeDuration}). */
    public static final String LIVE_EYE_SAFE_DURATION = "live-eye-safe-duration";
    /** Tier 2: {@code /offereye <viewer>}, the gift to one named viewer. Gifting to all needs no perk. */
    public static final String GIFT_LIVE_EYE = "gift-live-eye";
    /** Tier 3: takeover and book votes count for more ({@link VoteWeight}). */
    public static final String VOTE_WEIGHT = "vote-weight";
    /** Tier 3: disposable cameras take extra shots ({@link ExtraCamShots}). */
    public static final String EXTRA_CAM_SHOTS = "extra-cam-shots";

    private ArchivistPerks() {}
}
