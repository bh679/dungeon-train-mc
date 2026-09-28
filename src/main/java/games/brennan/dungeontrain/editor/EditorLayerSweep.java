package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalClear;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Erases whatever is standing in the editor's plot layer that no plot accounts for.
 *
 * <p>Every category lays its plots out from the same origin and only one is resident at a time
 * ({@link EditorStampedCategoryState}), so on a category switch the whole layer is supposed to be
 * empty once the previous category's plots are erased. What that per-plot erase cannot reach is
 * whatever the layout has stopped predicting: plots stamped before boxes were recorded, plots from
 * the disjoint-lane layout this world may have been saved under, a variant renamed or deleted since
 * it was stamped. Those sit exactly where the resident category now wants to put things. This sweep
 * catches them the way {@code PortalRoomEditor} used to catch leftovers in its own column, but over
 * every category's extent.</p>
 *
 * <p>Section by section, and only sections that hold anything: the layer is mostly sky, and asking
 * each chunk section whether it is all air is what keeps a sweep this wide cheap. <b>Loaded chunks
 * only</b> — a leftover in a chunk nobody has been near is not in anybody's way, and it is swept the
 * first time a switch runs with that chunk in. Self-healing, like the strays sweep.</p>
 *
 * <p>A category entry stamps the landing plot before the erases run, so the sweep takes a box to
 * leave alone; a section that straddles it is cleared around it ({@link #subtract}).</p>
 */
public final class EditorLayerSweep {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Past the furthest plot on each axis, how much further the sweep looks for what earlier layouts left. */
    static final int MARGIN_X = 120;
    static final int MARGIN_Z = TrackSidePlots.SLOT_STEP * 6;

    /**
     * The furthest {@code +Z} the pre-shared-origin layout reached: its track-side lane began at
     * Z=475 and stacked named variants in {@link TrackSidePlots#SLOT_STEP} slots past it. A world
     * saved under that layout has plots out there that nothing predicts any more, so the sweep
     * always reaches at least this far.
     */
    static final int LEGACY_MAX_Z = 600;

    private EditorLayerSweep() {}

    /** A plot's box with its 1-block outline cage, inclusive on every axis. */
    public static BoundingBox plotBox(BlockPos origin, Vec3i size) {
        return new BoundingBox(
            origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
            origin.getX() + size.getX(), origin.getY() + size.getY(), origin.getZ() + size.getZ());
    }

    /** The smallest box holding every box given, or null for none. */
    @Nullable
    public static BoundingBox unionOf(Collection<BoundingBox> boxes) {
        BoundingBox out = null;
        for (BoundingBox b : boxes) {
            out = out == null ? new BoundingBox(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()) : out.encapsulate(b);
        }
        return out;
    }

    /** Every plot box of {@code category}'s models, cages included. */
    public static List<BoundingBox> plotBoxes(ServerLevel level, EditorCategory category, CarriageDims dims) {
        List<BoundingBox> out = new ArrayList<>();
        for (Template model : category.models()) {
            BlockPos origin = model.editorPlotOrigin(level, dims);
            if (origin == null) continue;
            Vec3i size = model.plotSize(dims);
            if (size == null || size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) continue;
            out.add(plotBox(origin, size));
        }
        return out;
    }

    /**
     * {@code from} minus {@code cut}: the parts of {@code from} outside {@code cut}, as up to six
     * boxes (two per axis). {@code from} itself when they do not meet; nothing when it is inside.
     */
    public static List<BoundingBox> subtract(BoundingBox from, @Nullable BoundingBox cut) {
        if (cut == null || !from.intersects(cut)) return List.of(from);
        List<BoundingBox> out = new ArrayList<>(6);
        int x0 = from.minX(), x1 = from.maxX();
        int y0 = from.minY(), y1 = from.maxY();
        int z0 = from.minZ(), z1 = from.maxZ();
        // Slabs below and above the cut on X take the full Y×Z extent...
        if (x0 < cut.minX()) out.add(new BoundingBox(x0, y0, z0, cut.minX() - 1, y1, z1));
        if (x1 > cut.maxX()) out.add(new BoundingBox(cut.maxX() + 1, y0, z0, x1, y1, z1));
        // ...then, within the cut's X span, the slabs on Y take the full Z extent...
        int cx0 = Math.max(x0, cut.minX()), cx1 = Math.min(x1, cut.maxX());
        if (y0 < cut.minY()) out.add(new BoundingBox(cx0, y0, z0, cx1, cut.minY() - 1, z1));
        if (y1 > cut.maxY()) out.add(new BoundingBox(cx0, cut.maxY() + 1, z0, cx1, y1, z1));
        // ...and within both, what is left on Z.
        int cy0 = Math.max(y0, cut.minY()), cy1 = Math.min(y1, cut.maxY());
        if (z0 < cut.minZ()) out.add(new BoundingBox(cx0, cy0, z0, cx1, cy1, cut.minZ() - 1));
        if (z1 > cut.maxZ()) out.add(new BoundingBox(cx0, cy0, cut.maxZ() + 1, cx1, cy1, z1));
        return out;
    }

    /**
     * The XZ extent every plot of every category could reach in this world, plus the margins and
     * the legacy floor. Y is the plot layer: one below the floor to a portal room's ceiling.
     */
    public static BoundingBox layerRegion(ServerLevel overworld, CarriageDims dims) {
        int minX = -1;
        int minZ = -1;
        int maxX = minX;
        int maxZ = LEGACY_MAX_Z;
        for (EditorCategory category : EditorCategory.values()) {
            for (BoundingBox b : plotBoxes(overworld, category, dims)) {
                maxX = Math.max(maxX, b.maxX());
                maxZ = Math.max(maxZ, b.maxZ());
            }
        }
        for (int[] b : DungeonTrainWorldData.get(overworld).portalPlotBoxes().values()) {
            maxX = Math.max(maxX, b[0] + b[3]);
            maxZ = Math.max(maxZ, b[2] + b[5]);
        }
        int minY = EditorLayout.PLOT_Y - 1;
        int maxY = EditorLayout.PLOT_Y + PortalRoomLayout.MAX_HEIGHT + 2;
        return new BoundingBox(minX, minY, minZ, maxX + MARGIN_X, maxY, maxZ + MARGIN_Z);
    }

    /**
     * Erase every non-air section of {@code region} in a loaded chunk, leaving {@code keep} alone.
     *
     * @return blocks erased
     */
    public static int sweep(ServerLevel overworld, BoundingBox region, @Nullable BoundingBox keep) {
        int swept = 0;
        for (int cx = region.minX() >> 4; cx <= region.maxX() >> 4; cx++) {
            for (int cz = region.minZ() >> 4; cz <= region.maxZ() >> 4; cz++) {
                LevelChunk chunk = overworld.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (int sy = region.minY() >> 4; sy <= region.maxY() >> 4; sy++) {
                    int index = chunk.getSectionIndex(sy << 4);
                    if (index < 0 || index >= chunk.getSectionsCount()) continue;
                    if (chunk.getSection(index).hasOnlyAir()) continue;
                    BoundingBox box = new BoundingBox(
                        Math.max(region.minX(), cx << 4), Math.max(region.minY(), sy << 4), Math.max(region.minZ(), cz << 4),
                        Math.min(region.maxX(), (cx << 4) + 15), Math.min(region.maxY(), (sy << 4) + 15), Math.min(region.maxZ(), (cz << 4) + 15));
                    if (box.maxX() < box.minX() || box.maxY() < box.minY() || box.maxZ() < box.minZ()) continue;
                    for (BoundingBox part : subtract(box, keep)) {
                        swept += PortalClear.clearBoxRelit(overworld, part, PortalCorridorMask.NONE);
                    }
                }
            }
        }
        if (swept > 0) {
            LOGGER.info("[DungeonTrain] Editor layer sweep erased {} leftover blocks outside every plot", swept);
        }
        return swept;
    }
}
