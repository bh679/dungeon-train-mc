package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import javax.annotation.Nullable;

/**
 * Resolves a plot (shipyard) chunk to the sub-level that owns it — the lookup Sable's per-block
 * choke point needs to tell "a block changed on a ship" from "a block changed in the world".
 *
 * <p>A null chunk-holder means an ordinary world chunk, which is the overwhelming majority of block
 * changes, so that test comes first and costs one map lookup.</p>
 */
public final class CarriagePlotResolver {

    private CarriagePlotResolver() {}

    /** The live sub-level whose plot holds {@code chunk}, or {@code null} for a world chunk. */
    @Nullable
    public static ServerSubLevel subLevelAt(ServerLevel level, ChunkPos chunk) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        if (container.getChunkHolder(chunk) == null) return null;
        LevelPlot plot = container.getPlot(chunk);
        if (plot == null || !(plot.getSubLevel() instanceof ServerSubLevel serverSub)) return null;
        return serverSub;
    }
}
