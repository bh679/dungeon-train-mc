package games.brennan.dungeontrain.portal;

import net.minecraft.world.level.block.state.BlockState;

/**
 * A column of sampled world generation, waiting to be stamped into a
 * {@link PortalRoomMode#CHUNK_DIMENSION} room — one chunk's footprint
 * ({@link PortalChunkTerrain#SIZE}) by {@link PortalChunkTerrain#HEIGHT} tall, in room-local
 * coordinates.
 *
 * <p>Immutable once built and shared between threads: {@link PortalChunkTerrain} samples one of
 * these on a worker and hands it to the server thread, which only ever reads it. The states
 * themselves are the block registry's singletons, so the array is the whole of what is copied.</p>
 *
 * <p><b>Room-local, not world-local.</b> The sampled column's surface has already been slid onto
 * {@link PortalChunkTerrain#SURFACE_ROW} by the time it lands here, so a stamp is a straight copy
 * with no arithmetic of its own — which is what lets the same slice be re-stamped, unchanged, every
 * time the train drifts its structure along the track.</p>
 */
public final class PortalChunkSlice {

    private final PortalChunkTerrain.Source source;
    private final int width;
    private final int height;
    private final BlockState[] states;

    PortalChunkSlice(PortalChunkTerrain.Source source, int width, int height, BlockState[] states) {
        this.source = source;
        this.width = width;
        this.height = height;
        this.states = states;
    }

    /** Which dimension's generator this was sampled from. */
    public PortalChunkTerrain.Source source() {
        return source;
    }

    /** Footprint, in blocks — one chunk on both horizontal axes. */
    public int width() {
        return width;
    }

    /** How tall the column is, in blocks — two chunk sections. */
    public int height() {
        return height;
    }

    /** The state at a room-local cell, or null when the cell is outside the column. */
    public BlockState at(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= width) return null;
        return states[(y * width + z) * width + x];
    }
}
