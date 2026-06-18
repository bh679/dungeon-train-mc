package games.brennan.dungeontrain.ship;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Level-scoped façade for ship-physics operations. The single boundary
 * between Dungeon Train logic and the underlying ship-physics mod.
 *
 * <p>Implementations live in {@code ship.<modname>} sub-packages — currently
 * Valkyrien Skies via {@code ship.vs.VsShipyard}; planned swap to Sable.</p>
 *
 * <p>Get an instance via {@link Shipyards#of}.</p>
 */
public interface Shipyard {

    /**
     * Assemble a set of world-space block positions into a single managed
     * ship. The blocks are moved from world space into the ship's model
     * space and detached from world geometry.
     */
    ManagedShip assemble(Set<BlockPos> blocks, double density);

    /** Delete a previously-assembled ship and release its resources. */
    void delete(ManagedShip ship);

    /** Snapshot of every managed ship currently loaded in the level. */
    List<ManagedShip> findAll();

    /** The managed ship currently owning {@code pos}, or {@code null} if none. */
    @Nullable
    ManagedShip findAt(BlockPos pos);

    /** Convenience: true iff some managed ship currently owns {@code pos}. */
    default boolean isInShip(BlockPos pos) {
        return findAt(pos) != null;
    }

    /**
     * Force-load {@code ship}'s sub-level so the underlying physics mod keeps
     * it resident — and its backing world chunks loaded and ticking — even
     * when no player is close enough to keep it inside the simulation bubble.
     *
     * <p>Used to hold a freshly-generated trailing carriage long enough to
     * settle: a backward-riding player's newest carriages otherwise drift out
     * of the player-centred simulation distance and get culled before the
     * appender's placement settle completes (see
     * {@code TrainCarriageAppender} and the {@code backward-generation-stall}
     * note). Idempotent — calling it again on an already-force-loaded ship is a
     * no-op.</p>
     *
     * <p>Force-loading is <em>preventive</em>: it keeps a currently-loaded
     * sub-level loaded. It does not resurrect one the physics mod has already
     * culled — call this while the ship is still loaded (e.g. at spawn).</p>
     */
    void forceLoad(ManagedShip ship);

    /**
     * Release a force-load previously added via {@link #forceLoad}. Idempotent
     * — a no-op if {@code ship} was not force-loaded. Once released, the ship
     * may be culled normally when it leaves every player's simulation bubble.
     */
    void releaseForceLoad(ManagedShip ship);

    /**
     * Release every force-load this shipyard created (Dungeon Train's own
     * trailing-segment tickets), across all sub-levels in the level. Does
     * <em>not</em> touch force-loads from other sources (e.g. a manual
     * {@code /sable forceload} command).
     *
     * <p>Called on train wipe / bootstrap so no force-load ticket leaks across
     * a session boundary: some physics mods persist force-load tickets to disk
     * and resurrect the ticketed sub-levels on the next world load. Sweeping
     * here guarantees a clean slate.</p>
     */
    void releaseAllForceLoads();
}
