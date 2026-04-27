package games.brennan.dungeontrain.ship;

import games.brennan.dungeontrain.ship.vs.VsShipyard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;

/**
 * Static accessor for the active {@link Shipyard} implementation.
 *
 * <p>Wired to {@link VsShipyard} today. Swapping ship-physics mods means
 * changing the wiring in this one file — every caller across the rest of
 * the codebase keeps working unchanged.</p>
 */
public final class Shipyards {

    private Shipyards() {}

    /** Get the {@link Shipyard} for {@code level}. Server-side only. */
    public static Shipyard of(ServerLevel level) {
        return new VsShipyard(level);
    }

    /**
     * Convenience for {@link net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor}
     * and other code paths that hold a {@link LevelReader} rather than a
     * {@link ServerLevel}. Returns {@code false} on the client.
     */
    public static boolean isInShip(LevelReader level, BlockPos pos) {
        if (level instanceof ServerLevel sl) {
            return of(sl).isInShip(pos);
        }
        return false;
    }
}
