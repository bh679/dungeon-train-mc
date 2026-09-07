package games.brennan.dungeontrain.portal;

/**
 * How far a twin corridor may drift from its carriage before it is re-laid somewhere new.
 *
 * <h2>What the limit is really protecting</h2>
 * <p>A twin stands in the carriage's own chunk columns, and that is the whole reason a swap can land
 * in it: the client already has those columns. As the train rolls, the carriage leaves the columns
 * its twin was stamped into, and past some distance the destination is somewhere the player's client
 * knows nothing about. So the twin is erased and stamped again further along.</p>
 *
 * <p>The old answer was a flat 24 blocks — "within a chunk or two even at the smallest render
 * distances". Correct, and much tighter than it needs to be for the render distance almost anybody
 * plays at, which matters now that a re-stamp is known to cost something visible.</p>
 *
 * <h2>Why an occupied corridor gets more room</h2>
 * <p>Re-laying a twin erases every block and writes them again. For a player still on the train that
 * used to be free — they have not crossed, and the two corridors are identical — but their client
 * has been building that destination since they walked into the corridor
 * ({@code ClientPortalPrewarm}), and a re-stamp arrives as a burst of block changes that dirties
 * every section of it again. Do that in the last second before a crossing and the player arrives in
 * a room whose meshes were thrown away, which is the flash the prewarm exists to remove.</p>
 *
 * <p>So an occupied corridor tolerates more drift rather than none: the relocation still happens when
 * the twin would otherwise leave the columns the client has, which is the failure the limit is for.
 * It stops happening at 24 blocks under somebody three steps from walking through.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class PortalTwinDrift {

    /**
     * What an unoccupied pair tolerates, in blocks — the original limit, unchanged.
     *
     * <p>Nobody is walking into it, so nothing is lost by re-laying it early, and staying tight is
     * what keeps a train's worth of twins from wandering far from their carriages.</p>
     */
    public static final double BASE = 24.0;

    /**
     * The most an occupied one may tolerate, whatever the view distance says.
     *
     * <p>A ceiling rather than a formula's natural end: a player on a very long render distance would
     * otherwise let a twin trail a hundred and fifty blocks behind its carriage, and the further it
     * is from the train the more of the world between them has to stay loaded for it.</p>
     */
    public static final double OCCUPIED_CAP = 96.0;

    /** How much of the view distance the twin is allowed to spend — half, so it stays well inside. */
    private static final double VIEW_FRACTION = 0.5;

    private static final double BLOCKS_PER_CHUNK = 16.0;

    private PortalTwinDrift() {}

    /**
     * The drift this pair tolerates before it is re-stamped.
     *
     * @param corridorOccupied whether a player is standing in either half of this pair's corridor —
     *                         somebody about to cross, whose client is already building the far end
     * @param viewDistanceChunks the server's effective view distance; a nonsense value simply falls
     *                           back to {@link #BASE}, never to something larger than it
     */
    public static double allowance(boolean corridorOccupied, int viewDistanceChunks) {
        if (!corridorOccupied) return BASE;

        double half = Math.max(0, viewDistanceChunks) * BLOCKS_PER_CHUNK * VIEW_FRACTION;
        return Math.max(BASE, Math.min(OCCUPIED_CAP, half));
    }
}
