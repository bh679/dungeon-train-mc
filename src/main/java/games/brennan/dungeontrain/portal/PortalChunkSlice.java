package games.brennan.dungeontrain.portal;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;

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
    private final Map<Integer, CompoundTag> blockEntities;
    private final List<Occupant> occupants;

    /**
     * One entity the sample was generated with, and where it stood in the column — room-local, so a
     * room spawns it at the same place in its own terrain that it stood in the world's.
     */
    public record Occupant(CompoundTag nbt, double x, double y, double z) {}

    PortalChunkSlice(PortalChunkTerrain.Source source, int width, int height, BlockState[] states,
                     Map<Integer, CompoundTag> blockEntities, List<Occupant> occupants) {
        this.source = source;
        this.width = width;
        this.height = height;
        this.states = states;
        this.blockEntities = Map.copyOf(blockEntities);
        this.occupants = List.copyOf(occupants);
    }

    /**
     * The entities the sample was generated with — the biome's own animals and whatever the
     * structure was placed with.
     *
     * <p>Read once, when the room is first decorated: they are spawned into the world at that point
     * and live there like any other mob. Re-stamping a room does not re-read them, or a train that
     * drifted twice would leave three herds of sheep standing in the same field.</p>
     */
    public List<Occupant> occupants() {
        return occupants;
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
        if (!inside(x, y, z)) return null;
        return states[index(x, y, z)];
    }

    /**
     * The block entity the sample left at a room-local cell, as saved NBT, or null for the
     * overwhelming majority of cells that have none.
     *
     * <p>This is what carries a structure's <b>loot</b> across. A chest generated into a sample is a
     * chest block plus a block entity holding {@code LootTable} and {@code LootTableSeed} — the table
     * is rolled when a player first opens it, not when the structure is placed — so copying the
     * blocks alone would hand every village and pyramid in a chunk dimension a row of empty chests.
     * Carried as NBT because that is the form a freshly generated structure's block entities are
     * still in: pending, unpromoted, sitting in the chunk's own map.</p>
     */
    public CompoundTag blockEntityAt(int x, int y, int z) {
        if (!inside(x, y, z)) return null;
        return blockEntities.get(index(x, y, z));
    }

    private boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < width && y < height && z < width;
    }

    private int index(int x, int y, int z) {
        return (y * width + z) * width + x;
    }
}
