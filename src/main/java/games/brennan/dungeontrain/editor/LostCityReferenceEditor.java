package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.building.Buildings;
import games.brennan.dungeontrain.building.LostCityReferences;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Buildings tab's <b>Lost City</b> row — Big Lost City's official buildings ({@link LostCityReferences}),
 * stamped to look at and nothing else.
 *
 * <p>A second row behind the Buildings row, at the same height ({@link BuildingEditor#PLOT_Y}) and with the
 * same fixed stride, so every official building — each fits {@link Buildings#MAX_SIZE} — has its own slot and
 * hit-testing never loads a template. Stamped straight from the mod's own template through the server's
 * template manager, the way worldgen places it; never saved, copied or uploaded. {@code LostCityPlotGuard}
 * keeps anyone from changing the blocks.</p>
 */
public final class LostCityReferenceEditor {

    /** The editor model id every row carries. */
    public static final String MODEL_ID = "lost_city";

    private static final int STRIDE_X = Buildings.MAX_SIZE.getX() + EditorLayout.GAP + 2;
    /** One gap past the deepest a building can be, so the two rows never touch. */
    public static final int ROW_Z = Buildings.MAX_SIZE.getZ() + EditorLayout.GAP + 2;

    private LostCityReferenceEditor() {}

    /** The plot origin of official building {@code name}, or null when there is no such building. */
    public static BlockPos plotOrigin(String name) {
        List<LostCityReferences.Reference> all = LostCityReferences.all();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name().equals(name)) return slotOrigin(i);
        }
        return null;
    }

    private static BlockPos slotOrigin(int index) {
        return new BlockPos(index * STRIDE_X, BuildingEditor.PLOT_Y, ROW_Z);
    }

    /** The official building whose slot (cage included) holds {@code pos}, or empty. Resident category only. */
    public static Optional<String> plotContaining(BlockPos pos) {
        if (!EditorStampedCategoryState.isActive(EditorCategory.BUILDINGS)) return Optional.empty();
        return slotContaining(pos);
    }

    /** {@link #plotContaining} without the residency check — what the guard asks. */
    public static Optional<String> slotContaining(BlockPos pos) {
        List<LostCityReferences.Reference> all = LostCityReferences.all();
        int index = Math.floorDiv(pos.getX() + 1, STRIDE_X);
        if (index < 0 || index >= all.size()) return Optional.empty();
        boolean inside = EditorPlotBox.contains(pos, slotOrigin(index), Buildings.MAX_SIZE);
        return inside ? Optional.of(all.get(index).name()) : Optional.empty();
    }

    /** {@code name}'s size — its template's, measured once. */
    public static Vec3i plotSize(ServerLevel level, String name) {
        return LostCityReferences.sizeOf(level.getServer(), name);
    }

    /** Stand {@code player} on {@code name}'s plot as it stands: on the roof, or in the middle when {@code centre}. */
    public static void walkTo(ServerPlayer player, ServerLevel level, String name, boolean centre) {
        BlockPos origin = plotOrigin(name);
        if (origin == null) return;
        EditorSessions.rememberReturn(player);
        EditorPlotArrival.land(player, level, origin, plotSize(level, name), !centre,
            EditorPlotArrival.Inside.CENTRE, null);
    }

    /** Every official building's plot, as queued jobs — added to the Buildings tab's fill. */
    public static List<EditorStampQueue.Job> stampAllPlotJobs(ServerLevel level) {
        List<EditorStampQueue.Job> jobs = new ArrayList<>();
        for (LostCityReferences.Reference ref : LostCityReferences.all()) {
            jobs.add(new EditorStampQueue.Job("stamp lost city " + ref.name(), () -> stampPlot(level, ref.name())));
        }
        return jobs;
    }

    /** Stamp {@code name}'s plot from Big Lost City's own template. */
    public static void stampPlot(ServerLevel level, String name) {
        BlockPos origin = plotOrigin(name);
        Optional<LostCityReferences.Reference> ref = LostCityReferences.find(name);
        if (origin == null || ref.isEmpty()) return;
        eraseSlot(level, origin);
        Optional<StructureTemplate> template = LostCityReferences.template(level.getServer(), ref.get());
        paintCage(level, origin, plotSize(level, name));
        template.ifPresent(t -> t.placeInWorld(level, origin, origin,
            new StructurePlaceSettings().setIgnoreEntities(true), level.getRandom(), CarriageStampGuard.STAMP_FLAGS));
    }

    /** Erase every official building's slot. */
    public static void clearAllPlots(ServerLevel level) {
        List<LostCityReferences.Reference> all = LostCityReferences.all();
        for (int i = 0; i < all.size(); i++) eraseSlot(level, slotOrigin(i));
    }

    private static void eraseSlot(ServerLevel level, BlockPos origin) {
        EditorPlotBox.eraseWithHeadroom(level, origin, Buildings.MAX_SIZE, EditorPlotCage.QUIET);
    }

    private static void paintCage(ServerLevel level, BlockPos origin, Vec3i size) {
        EditorPlotCage.setOutline(level, origin, size, EditorPlotCage.OUTLINE_BLOCK, EditorPlotCage.QUIET);
    }
}
