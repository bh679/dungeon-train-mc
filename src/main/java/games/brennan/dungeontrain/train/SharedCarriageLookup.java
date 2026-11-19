package games.brennan.dungeontrain.train;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.ship.ManagedShip;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.joml.Vector3d;

import java.util.List;
import java.util.UUID;

/**
 * Which drifting carriage, if any, a given block position belongs to.
 *
 * <p>Two halves, not interchangeable: {@link #byBlockPos} resolves a block (block events, which
 * fire in sub-level plot space), {@link #byEntity} resolves whoever is standing on a carriage,
 * starting from their footing via {@code CarriageDeck.carriageUnder}.</p>
 *
 * <p>Same sequence {@code SableBlockChangeGuardMixin} resolves in: sub-level container → plot →
 * sub-level id → footprint match. NeoForge's block events already fire in sub-level plot space,
 * so no world→ship transform is needed here.</p>
 */
public final class SharedCarriageLookup {

    private SharedCarriageLookup() {}

    /**
     * The drifting carriage owning the block at {@code pos}, or null when there is none — an
     * ordinary world chunk, a sub-level that holds no shared carriage, or a position outside
     * every registered footprint. Cheap: an ordinary world chunk short-circuits at the second
     * step, before any registry work.
     *
     * <p>Callers that act on a live carriage should also reject {@code inst.isCulled()} — a
     * culled instance still resolves.</p>
     */
    public static SharedCarriageRegistry.Instance byBlockPos(ServerLevel level, BlockPos pos) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        ChunkPos cpos = new ChunkPos(pos);
        if (container.getChunkHolder(cpos) == null) return null; // ordinary world chunk
        LevelPlot plot = container.getPlot(cpos);
        if (plot == null || !(plot.getSubLevel() instanceof ServerSubLevel serverSub)) return null;
        UUID subLevelId = serverSub.getUniqueId();
        if (!SharedCarriageRegistry.hasSubLevel(subLevelId)) return null;
        return SharedCarriageRegistry.resolve(subLevelId, pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * The drifting carriage {@code entity} is standing on, or null — off the train, on a carriage
     * that holds no shared build, or airborne (footing needs a block at the feet, so a jump reads as
     * off for a poll or two). Culled instances resolve too; reject {@code inst.isCulled()} as needed.
     */
    public static SharedCarriageRegistry.Instance byEntity(ServerLevel level, Entity entity) {
        List<Trains.Carriage> carriages = Trains.allCarriages(level);
        if (carriages.isEmpty()) return null;
        Trains.Carriage c = CarriageDeck.carriageUnder(carriages, entity);
        if (c == null) return null;
        ManagedShip ship = c.ship();
        Vector3d local = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
        ship.worldToShip(local);
        return SharedCarriageRegistry.resolve(ship.subLevelId(),
                (int) Math.floor(local.x), (int) Math.floor(local.y), (int) Math.floor(local.z));
    }
}
