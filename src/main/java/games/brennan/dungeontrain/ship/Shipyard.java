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
     * Constrain two adjacent carriage sub-levels so their Y, Z, and
     * rotation axes stay aligned in world space — physics-engine-enforced
     * each tick. The X axis (= train velocity direction) is left free so
     * each carriage's kinematic driver can advance it independently.
     *
     * <p>Used to suppress per-carriage jitter caused by collision impulses
     * pushing one body's Y/Z/rotation slightly off between server-tick
     * teleports. With the constraint in place, the physics solver pulls
     * the bodies back into alignment every physics tick.</p>
     *
     * <p>Default implementation is a no-op for physics mods without a
     * constraint API.</p>
     */
    default void lockAdjacentYZRotation(ManagedShip a, ManagedShip b) {}
}
