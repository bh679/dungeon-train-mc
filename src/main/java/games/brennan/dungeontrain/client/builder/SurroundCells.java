package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.BuilderSurroundings;
import games.brennan.dungeontrain.track.TrackPlacer;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a {@link BuilderSurroundings.Source} into blocks, on the client.
 *
 * <p>The declaration says <em>a carriage of this variant</em>; this says what a carriage of that
 * variant is made of. Kept apart because the two questions have different shapes: a declaration is
 * a handful of records recomputed every tick, and the blocks behind it are thousands of cells read
 * from a template, worth reading once and keeping.</p>
 *
 * <p><b>Cached by source, not by placement.</b> A carriage two slots down and a carriage one slot
 * down are the same blocks at different origins, so the same entry serves both, and a group of three
 * costs one read rather than three.</p>
 *
 * <p>Nothing here is new machinery. The client could already read a template's cells for the Open
 * grid's spinning tiles ({@link BuilderTileTemplates#cells}) and for the track ghosts
 * ({@link BuilderGhostTemplates}); this routes each source at the one that already knows how. That
 * it works at all rests on a builder world being single-player by construction, so the template
 * stores are in this same process — the same thing {@code EditorTemplateLists} relies on.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SurroundCells {

    /**
     * Session-scoped, and dropped on logout and on a resource reload by {@link #clear}.
     *
     * <p>Cleared for the reason the Open grid's mesh cache is: an entry is only valid for the world
     * that produced it, and a template edited between two opens must not come back stale.</p>
     */
    private static final Map<BuilderSurroundings.Source, Map<BlockPos, BlockState>> CACHE =
            new ConcurrentHashMap<>();

    private SurroundCells() {}

    public static void clear() {
        CACHE.clear();
    }

    /** The blocks of one source, local to its own origin. Empty is an ordinary answer. */
    public static Map<BlockPos, BlockState> of(BuilderSurroundings.Source source, CarriageDims dims) {
        if (source == null || dims == null) {
            return Map.of();
        }
        return CACHE.computeIfAbsent(source, s -> resolve(s, dims));
    }

    private static Map<BlockPos, BlockState> resolve(BuilderSurroundings.Source source,
                                                     CarriageDims dims) {
        return switch (source) {
            case BuilderSurroundings.Source.Carriage c -> carriage(c.variantId());
            case BuilderSurroundings.Source.CarriageSkin s -> skinOf(carriage(s.variantId()), dims);
            case BuilderSurroundings.Source.FlatbedPad p -> pad(p.side(), dims);
            case BuilderSurroundings.Source.TrackCorridor t -> corridor(t.fromX(), t.toX(), dims);
            case BuilderSurroundings.Source.RoomTile r -> room(r.name());
            case BuilderSurroundings.Source.RoomShell r -> room(r.name());
            // The line around an open track plot is a whole composed scene rather than one
            // template, and BuilderTrackSceneGhosts already builds it from the same picks the world
            // would make. Resolved by the renderer, which has the plot to build it against.
            case BuilderSurroundings.Source.TrackScene ignored -> Map.of();
        };
    }

    private static Map<BlockPos, BlockState> carriage(String variantId) {
        if (variantId == null || variantId.isEmpty()) {
            return Map.of();
        }
        return BuilderTileTemplates.cells(BuilderPhotoPaths.Kind.CARRIAGE, variantId, null, null);
    }

    private static Map<BlockPos, BlockState> room(String name) {
        if (name == null || name.isEmpty()) {
            return Map.of();
        }
        return BuilderTileTemplates.cells(BuilderPhotoPaths.Kind.PORTAL_ROOM, name, null, null);
    }

    /**
     * The perimeter skin of a carriage — walls, roof and deck, without the interior.
     *
     * <p>Filtered from the whole carriage rather than read separately, so the skin and the carriage
     * either side of it can never disagree about what a carriage looks like.</p>
     */
    private static Map<BlockPos, BlockState> skinOf(Map<BlockPos, BlockState> whole,
                                                    CarriageDims dims) {
        Map<BlockPos, BlockState> skin = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : whole.entrySet()) {
            BlockPos p = e.getKey();
            if (BuilderSurroundings.onSkin(p.getX(), p.getY(), p.getZ(), dims)) {
                skin.put(p, e.getValue());
            }
        }
        return skin;
    }

    /**
     * One half-flatbed pad, sliced out of the FLATBED carriage the way the real one is.
     *
     * <p>{@code CarriagePlacer} builds a pad by trimming that template to {@code halfPadLen} on X and
     * mirroring the FRONT one across it — the same two steps here, against the same template, so a
     * ghost pad and a real pad are the same blocks. Deriving it rather than being sent it is what
     * lets a one-carriage world show pads it never stamped.</p>
     */
    private static Map<BlockPos, BlockState> pad(CarriagePlacer.HalfPadSide side, CarriageDims dims) {
        Map<BlockPos, BlockState> flatbed =
                carriage(CarriagePlacer.CarriageType.FLATBED.id());
        int padLength = CarriagePlacer.halfPadLen(dims);
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : flatbed.entrySet()) {
            BlockPos p = e.getKey();
            if (p.getX() >= padLength) {
                continue;
            }
            int x = side == CarriagePlacer.HalfPadSide.FRONT ? padLength - 1 - p.getX() : p.getX();
            out.put(new BlockPos(x, p.getY(), p.getZ()), e.getValue());
        }
        return out;
    }

    /**
     * The bed and rails from {@code fromX} to {@code toX}, tile by tile.
     *
     * <p>Each {@link TrackPlacer#TILE_LENGTH}-wide tile is picked from the registry against the
     * world seed exactly as the generator picks it, so the ghosted line is the line that would be
     * there — not a stand-in for it.</p>
     */
    private static Map<BlockPos, BlockState> corridor(int fromX, int toX, CarriageDims dims) {
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        long seed = BuilderGhostTemplates.worldSeed();
        long firstTile = Math.floorDiv(fromX, TrackPlacer.TILE_LENGTH);
        long lastTile = Math.floorDiv(toX, TrackPlacer.TILE_LENGTH);

        for (long tile = firstTile; tile <= lastTile; tile++) {
            Map<BlockPos, BlockState> cells =
                    BuilderGhostTemplates.cellsForPick(TrackKind.TILE, seed, tile, dims);
            int tileX = (int) (tile * TrackPlacer.TILE_LENGTH);
            for (Map.Entry<BlockPos, BlockState> e : cells.entrySet()) {
                BlockPos p = e.getKey();
                int worldX = tileX + p.getX();
                if (worldX < fromX || worldX > toX) {
                    continue;
                }
                // Local to the corridor's own origin, which the declaration puts at the world zero
                // the tile grid is measured from.
                out.put(new BlockPos(worldX, p.getY(), p.getZ()), e.getValue());
            }
        }
        return out;
    }
}
