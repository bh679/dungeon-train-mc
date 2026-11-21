package games.brennan.dungeontrain.perks.effects.archivist;

/**
 * Vote Weight: a takeover vote or a book vote counts for {@code 1 + rank}, with no cap. The relay
 * tallies votes, so the weight rides on the vote's request body ({@code weight}); a relay that does
 * not read it counts the vote once. Pure.
 */
public final class VoteWeight {

    /** One extra vote per rank. Balancing knob. */
    public static final int PER_RANK = 1;

    private VoteWeight() {}

    /** The weight for {@code rank}: 1 at rank 0 or less. */
    public static int weight(int rank) {
        return 1 + Math.max(0, rank) * PER_RANK;
    }
}
