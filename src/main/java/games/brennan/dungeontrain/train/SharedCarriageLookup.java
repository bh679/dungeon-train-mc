package games.brennan.dungeontrain.train;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.UUID;

/**
 * Which drifting carriage, if any, a given block position belongs to.
 *
 * <p>The block-position half of carriage resolution, kept in one place because more than one
 * listener needs it. (The other half — which carriage a <em>player</em> is standing on — starts
 * from the player's footing instead and lives in {@code CarriageDeck.carriageUnder}; the two are
 * not interchangeable.)</p>
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
}
