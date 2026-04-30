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
}
