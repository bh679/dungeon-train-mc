package games.brennan.dungeontrain.client.constellation;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A cosmic carpet sits on whatever is under it: on a block less than a block tall (a slab, a
 * pressure plate) it drops down onto that block's collision height instead of hanging over the gap. Client side,
 * for drawing it and its glow.
 */
public final class ConstellationCarpetSink {

    private ConstellationCarpetSink() {}

    /**
     * How far down the carpet at {@code packed} draws, in blocks: {@code 1 - top} of a short block
     * under it, else 0. {@code sub}: the carriage whose plot holds it, or null for the main world.
     */
    public static double sink(long packed, ClientSubLevel sub) {
        BlockPos below = BlockPos.of(packed).below();
        BlockGetter getter = sub == null ? Minecraft.getInstance().level : chunkAt(sub, below);
        if (getter == null) return 0;
        // Its collision height (what you stand on); a block you walk through (a pressure plate) by its
        // look; and never under its look (snow stands taller than you sink into it), or it would bury the carpet.
        net.minecraft.world.level.block.state.BlockState state = getter.getBlockState(below);
        return sinkFor(Math.max(top(state.getCollisionShape(getter, below)), top(state.getShape(getter, below))));
    }

    private static double top(VoxelShape shape) {
        return shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
    }

    /** {@code top}: the height of the block below's shape (0 for none). */
    static double sinkFor(double top) {
        return top > 0 && top < 1 ? 1 - top : 0;
    }

    /** The plot chunk of {@code sub} holding {@code pos}, or null when it isn't loaded. */
    static LevelChunk chunkAt(ClientSubLevel sub, BlockPos pos) {
        LevelPlot plot = sub.getPlot();
        if (plot == null) return null;
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk != null && chunk.getPos().x == cx && chunk.getPos().z == cz) return chunk;
        }
        return null;
    }
}
