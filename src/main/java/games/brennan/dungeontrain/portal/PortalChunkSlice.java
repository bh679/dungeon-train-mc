package games.brennan.dungeontrain.portal;

import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.biome.Biome;
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
     * The sampled chunk's biomes over the cut, one per quart (4×4×4 cells) — what the room is
     * painted with so its grass, leaves and water are tinted as the sample was. Null when unknown.
     */
    private final Holder<Biome>[] biomes;

    /**
     * One entity the sample was generated with, and where it stood in the column — room-local, so a
     * room spawns it at the same place in its own terrain that it stood in the world's.
     */
    public record Occupant(CompoundTag nbt, double x, double y, double z) {}

    PortalChunkSlice(PortalChunkTerrain.Source source, int width, int height, BlockState[] states,
                     Map<Integer, CompoundTag> blockEntities, List<Occupant> occupants) {
        this(source, width, height, states, blockEntities, occupants, null);
    }

    PortalChunkSlice(PortalChunkTerrain.Source source, int width, int height, BlockState[] states,
                     Map<Integer, CompoundTag> blockEntities, List<Occupant> occupants,
                     Holder<Biome>[] biomes) {
        this.biomes = biomes;
        this.source = source;
        this.width = width;
        this.height = height;
        this.states = states;
        this.blockEntities = Map.copyOf(blockEntities);
        this.occupants = List.copyOf(occupants);
        this.ground = null;
        this.changes = null;
        this.biomesChanged = true;
    }

    /** {@code decorated}, said to differ from {@code ground} at exactly {@code changes}. */
    private PortalChunkSlice(PortalChunkSlice decorated, PortalChunkSlice ground, int[] changes,
                             boolean biomesChanged) {
        this.source = decorated.source;
        this.width = decorated.width;
        this.height = decorated.height;
        this.states = decorated.states;
        this.blockEntities = decorated.blockEntities;
        this.occupants = decorated.occupants;
        this.biomes = decorated.biomes;
        this.ground = ground;
        this.changes = changes;
        this.biomesChanged = biomesChanged;
    }

    // ---- what a decorated slice changed (PortalChunkDecorationDiff) ----------

    /**
     * The ground slice this one was diffed against, or null for a slice that carries no diff — a
     * ground slice itself, or a decorated one whose ground was not in hand.
     */
    private final PortalChunkSlice ground;

    /** The cell indices that differ from {@link #ground}, ascending (so bottom-up); null without one. */
    private final int[] changes;

    /** Whether any quart's biome differs from {@link #ground}'s; true when unknown. */
    private final boolean biomesChanged;

    /** This slice, carrying the cells it differs from {@code ground} at. */
    PortalChunkSlice withChanges(PortalChunkSlice ground, int[] changes, boolean biomesChanged) {
        return new PortalChunkSlice(this, ground, changes, biomesChanged);
    }

    /** Whether this slice was diffed against exactly {@code slice} — by identity, not by content. */
    boolean decorates(PortalChunkSlice slice) {
        return slice != null && ground == slice && changes != null;
    }

    /** The changed cell indices, ascending; never null for a slice that {@link #decorates} something. */
    int[] changes() {
        return changes;
    }

    /** Whether the biomes differ from the ground's. */
    boolean biomesChanged() {
        return biomesChanged;
    }

    /** Cells in the column: {@code width * width * height}. */
    int cellCount() {
        return states.length;
    }

    /** The state at cell index {@code index}, as {@link #at} reads it. */
    BlockState stateAtIndex(int index) {
        return states[index];
    }

    /** The block entity at cell index {@code index}, or null. */
    CompoundTag blockEntityAtIndex(int index) {
        return blockEntities.get(index);
    }

    /** The cell indices holding a block entity. */
    java.util.Set<Integer> blockEntityIndices() {
        return blockEntities.keySet();
    }

    /** The sampled biome per quart, or null; shared, never written. */
    Holder<Biome>[] biomesRaw() {
        return biomes;
    }

    /** Row of cell index {@code index}. */
    int yOf(int index) {
        return index / (width * width);
    }

    /** Z of cell index {@code index}. */
    int zOf(int index) {
        return (index / width) % width;
    }

    /** X of cell index {@code index}. */
    int xOf(int index) {
        return index % width;
    }

    /** The pour's strip ({@link PortalChunkPour}) holding cell index {@code index}: its row, then its Z. */
    int stripOf(int index) {
        return index / width;
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

    /** Quarts across the footprint. */
    static int quarts(int blocks) {
        return (blocks + 3) >> 2;
    }

    /** The sampled biome over a room-local cell, or null when outside the cut or not sampled. */
    public Holder<Biome> biomeAt(int x, int y, int z) {
        if (biomes == null || !inside(x, y, z)) return null;
        int qw = quarts(width);
        return biomes[((y >> 2) * qw + (z >> 2)) * qw + (x >> 2)];
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
