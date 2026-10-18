package games.brennan.dungeontrain.ship.sable;

/**
 * Pure policy behind {@code PhysicsChunkTicketManagerNoSyncLoadMixin}: when may Sable's physics
 * ticket manager skip a <em>blocking</em> chunk load on the server thread?
 *
 * <p>Sable's {@code PhysicsChunkTicketManager.update} makes every force-loaded sub-level
 * "inhabit" each chunk column under its box: it adds an ENTITY_TICKING chunk ticket (which
 * already schedules an async load) and then calls {@code getChunk(x, z, FULL, true)}, blocking
 * the server thread until that chunk is loaded or generated. Its second pass then calls
 * {@code addTicket → Level.getChunk(x, z)} per section, which blocks the same way. One blocking
 * load per (sub-level, chunk) pair: when a pause/resume or save hold newly force-loads a long
 * train, the first tick paid 138 of them back to back — a 26 s freeze (player log 17 Oct 2026,
 * 99 resident groups).</p>
 *
 * <p>Plot chunks (inside the Sable container's bounds) are Sable-managed and always resolve
 * instantly, so they keep the original path. A world chunk that is already loaded also resolves
 * instantly. Only a world chunk that isn't loaded yet is skipped: the ticket that was just added
 * loads it asynchronously, and the ticket manager retries the section on a later tick.</p>
 */
public final class PhysicsTicketNoSyncLoad {

    private PhysicsTicketNoSyncLoad() {}

    /**
     * @param inPlotBounds the chunk lies inside the Sable plot container's bounds
     * @param loadedNow    the chunk is already fully loaded ({@code getChunkNow != null})
     * @return {@code true} when the blocking load should be skipped
     */
    public static boolean shouldSkipBlockingLoad(boolean inPlotBounds, boolean loadedNow) {
        return !inPlotBounds && !loadedNow;
    }
}
