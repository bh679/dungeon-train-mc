package games.brennan.dungeontrain.ship;

import games.brennan.dungeontrain.ship.sable.SableShipyard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Static accessor for the active {@link Shipyard} implementation.
 *
 * <p>Wired to {@link SableShipyard} (Phase 2 of the 1.21.1 migration).
 * Previously wrapped Valkyrien Skies' {@code LoadedServerShip}; VS upstream
 * has not shipped a 1.21.1/NeoForge build, so we pivoted to Sable
 * (https://github.com/ryanhcode/sable, PolyForm Shield 1.0.0).</p>
 *
 * <p>Swapping ship-physics mods is one file change — every caller across
 * the rest of the codebase keeps working unchanged.</p>
 *
 * <p>Caches one {@link SableShipyard} per {@link ServerLevel} so the
 * shipyard's internal {@code ManagedShip} wrapper cache survives across
 * calls. Otherwise stateful wrapper data — most importantly the kinematic
 * driver — would be silently lost between {@code of(level)} calls because
 * each fresh {@code SableShipyard} starts with an empty wrapper map.</p>
 */
public final class Shipyards {

    private Shipyards() {}

    /** Per-level shipyard cache. Weak so unloaded levels don't leak. */
    private static final Map<ServerLevel, SableShipyard> CACHE =
        Collections.synchronizedMap(new WeakHashMap<>());

    /** Get the {@link Shipyard} for {@code level}. Server-side only. */
    public static Shipyard of(ServerLevel level) {
        return CACHE.computeIfAbsent(level, SableShipyard::new);
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
