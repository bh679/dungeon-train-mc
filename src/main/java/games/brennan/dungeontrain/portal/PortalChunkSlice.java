package games.brennan.dungeontrain.portal;

import net.minecraft.world.level.block.state.BlockState;

/**
 * A cube of sampled world generation, waiting to be stamped into a
 * {@link PortalRoomMode#CHUNK_DIMENSION} room — {@link PortalChunkTerrain#SIZE} blocks on every
 * axis, in room-local coordinates.
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
    private final int size;
    private final BlockState[] states;

    PortalChunkSlice(PortalChunkTerrain.Source source, int size, BlockState[] states) {
        this.source = source;
        this.size = size;
        this.states = states;
    }

    /** Which dimension's generator this was sampled from. */
    public PortalChunkTerrain.Source source() {
        return source;
    }

    /** Edge length, in blocks — the same on all three axes. */
    public int size() {
        return size;
    }

    /** The state at a room-local cell, or null when the cell is outside the cube. */
    public BlockState at(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= size || y >= size || z >= size) return null;
        return states[(y * size + z) * size + x];
    }
}
