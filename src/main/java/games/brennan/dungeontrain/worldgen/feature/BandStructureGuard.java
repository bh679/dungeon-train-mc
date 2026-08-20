package games.brennan.dungeontrain.worldgen.feature;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.util.LogFirstN;
import games.brennan.dungeontrain.worldgen.structure.ModStructureTypes;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.neoforge.common.Tags;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The blocks {@link NetherTransitionFeature} must not overwrite: the pieces of the band's own Nether
 * structures, already standing in this chunk.
 *
 * <p>Ordering makes this necessary. Structures are placed during the structure steps; the Nether core fill
 * runs at {@code top_layer_modification}, the very last decoration step, and replaces whole columns. Left
 * alone it would bury a fortress it had no idea was there. The End band never had this problem — its core
 * generates empty, so {@code DisintegrationFeature} can simply stamp around whatever is already standing.
 * The Nether core is full of the overworld mountain it is about to replace, so "is this block occupied" says
 * nothing — the structures' own pieces have to say where they are.</p>
 *
 * <p>Pieces, not whole structures: a fortress's overall bounding box is mostly empty air between bridges,
 * and protecting that would leave a large netherrack-free void around it. Protecting each piece leaves the
 * fill to pack netherrack right up against the walls, which is what the real Nether looks like.</p>
 *
 * <p>And within a piece, blocks rather than the box — see {@link #preserves}. A piece's box is not the same
 * thing as the piece: a bastion's templates claim their whole footprint but write {@code structure_void}
 * over every cell they don't own (courtyards, the gaps between wings), and a {@code structure_void} cell
 * leaves whatever was already there. Skipping the box wholesale therefore left the raised overworld
 * mountain standing inside it, and bastions generated cased in stone.</p>
 *
 * <p>Cross-chunk pieces are covered because the chunk's structure <em>references</em> are, by definition,
 * every start whose box reaches this chunk — including starts that began in a neighbour and were written
 * here while that neighbour decorated.</p>
 */
final class BandStructureGuard {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN COLLECT_ERRORS = new LogFirstN(5);

    /** No band structure touches this chunk — the overwhelmingly common case. */
    private static final BandStructureGuard EMPTY = new BandStructureGuard(List.of());
    /** No band structure touches this column. */
    static final Column OPEN_COLUMN = y -> false;

    private final List<BoundingBox> pieces;

    private BandStructureGuard(List<BoundingBox> pieces) {
        this.pieces = pieces;
    }

    /**
     * True if this block must survive the core fill: either a block one of the band's structures actually
     * placed, or air it carved. False for the untouched overworld terrain that a piece's
     * {@code structure_void} cells left sitting inside its bounding box — that is exactly what the fill
     * should be turning into netherrack, lava or air.
     *
     * <p>The <em>replaceable</em> side is the one enumerated, deliberately. A block missing from this list
     * leaves a stray stone block inside a fortress — cosmetic. A block missing from the opposite list would
     * delete part of a bastion.</p>
     *
     * <p>Gravel and sand are left out even though they are genuine overworld terrain: nether ruined portals
     * scatter gravel as part of their own design, and eating it would damage every portal to tidy a rarer
     * blemish.</p>
     */
    static boolean preserves(BlockState state) {
        if (state.isAir()) return true;              // carved by the structure — never fill it back in
        return !isNaturalOverworldTerrain(state);
    }

    /** Terrain the overworld generator, its surface rules, its ores and its vegetation produced. */
    private static boolean isNaturalOverworldTerrain(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD)      // stone, granite, diorite, andesite, tuff, deepslate
            || state.is(BlockTags.DIRT)                      // dirt, grass block, podzol, mycelium, mud, moss
            || state.is(BlockTags.TERRACOTTA)
            || state.is(Tags.Blocks.ORES)
            || state.is(Blocks.CLAY)
            || state.is(BlockTags.SNOW)
            || state.is(Blocks.SNOW_BLOCK)
            || state.is(BlockTags.ICE)
            || state.is(Blocks.POWDER_SNOW)
            || state.getFluidState().is(FluidTags.WATER)
            || NetherTransitionFeature.isStrippableFoliage(state);
    }

    /** One column's protected Y range(s). */
    @FunctionalInterface
    interface Column {
        /**
         * True if this Y is inside one of the band's structure pieces. The caller still asks
         * {@link #preserves} about the block itself — a piece's box covers terrain it never touched.
         */
        boolean protects(int y);
    }

    /**
     * Collect the band-structure piece boxes reaching into {@code chunkPos}. Never {@code null}: any failure
     * resolving the structure starts yields an empty guard, so the core still fills — a buried fortress is a
     * far smaller problem than a chunk that fails to generate.
     */
    static BandStructureGuard collect(WorldGenLevel level, ChunkPos chunkPos) {
        try {
            if (!(level instanceof WorldGenRegion region)) return EMPTY;
            List<StructureStart> starts = level.getLevel().structureManager()
                    .forWorldGenRegion(region)
                    .startsForStructure(chunkPos, structure -> ModStructureTypes.isBandStructure(structure.type()));
            if (starts.isEmpty()) return EMPTY;

            int minX = chunkPos.getMinBlockX();
            int maxX = chunkPos.getMaxBlockX();
            int minZ = chunkPos.getMinBlockZ();
            int maxZ = chunkPos.getMaxBlockZ();
            List<BoundingBox> pieces = new ArrayList<>();
            for (StructureStart start : starts) {
                for (StructurePiece piece : start.getPieces()) {
                    BoundingBox box = piece.getBoundingBox();
                    if (box.maxX() >= minX && box.minX() <= maxX && box.maxZ() >= minZ && box.minZ() <= maxZ) {
                        pieces.add(box);
                    }
                }
            }
            return pieces.isEmpty() ? EMPTY : new BandStructureGuard(pieces);
        } catch (Throwable t) {
            COLLECT_ERRORS.error(LOGGER,
                    "[DungeonTrain] Could not resolve band structures in " + chunkPos
                        + "; filling the core over them", t);
            return EMPTY;
        }
    }

    /** True when no band structure reaches this chunk at all — lets the fill skip the per-column work. */
    boolean isEmpty() {
        return pieces.isEmpty();
    }

    /**
     * The guard for one column. Resolved once per column so the per-block test only ever walks the few
     * pieces that actually stand over it (usually none, in which case it is {@link #OPEN_COLUMN}).
     */
    Column column(int worldX, int worldZ) {
        if (pieces.isEmpty()) return OPEN_COLUMN;
        List<BoundingBox> over = null;
        for (BoundingBox box : pieces) {
            if (worldX >= box.minX() && worldX <= box.maxX() && worldZ >= box.minZ() && worldZ <= box.maxZ()) {
                if (over == null) over = new ArrayList<>(2);
                over.add(box);
            }
        }
        if (over == null) return OPEN_COLUMN;
        List<BoundingBox> boxes = over;
        return y -> {
            for (BoundingBox box : boxes) {
                if (y >= box.minY() && y <= box.maxY()) return true;
            }
            return false;
        };
    }
}
