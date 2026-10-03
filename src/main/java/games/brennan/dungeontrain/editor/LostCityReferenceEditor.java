package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.building.Buildings;
import games.brennan.dungeontrain.building.LostCityReferences;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
    private static final BlockState OUTLINE = Blocks.BEDROCK.defaultBlockState();
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

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
        if (EditorStampedCategoryState.current().orElse(null) != EditorCategory.BUILDINGS) return Optional.empty();
        return slotContaining(pos);
    }

    /** {@link #plotContaining} without the residency check — what the guard asks. */
    public static Optional<String> slotContaining(BlockPos pos) {
        List<LostCityReferences.Reference> all = LostCityReferences.all();
        int index = Math.floorDiv(pos.getX() + 1, STRIDE_X);
        if (index < 0 || index >= all.size()) return Optional.empty();
        BlockPos o = slotOrigin(index);
        Vec3i s = Buildings.MAX_SIZE;
        boolean inside = pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + s.getX()
            && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + s.getY() + 2
            && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + s.getZ();
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
        CarriageEditor.rememberReturn(player);
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
        Vec3i max = Buildings.MAX_SIZE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = -1; x <= max.getX(); x++) {
            for (int y = -1; y <= max.getY() + 2; y++) {
                for (int z = -1; z <= max.getZ(); z++) {
                    p.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState current = level.getBlockState(p);
                    if (current.isAir()) continue;
                    if (current.hasBlockEntity()) level.removeBlockEntity(p);
                    level.setBlock(p, air, QUIET);
                }
            }
        }
    }

    /** The twelve edges of the box one block outside the plot. */
    private static void paintCage(ServerLevel level, BlockPos origin, Vec3i size) {
        int x0 = origin.getX() - 1, y0 = origin.getY() - 1, z0 = origin.getZ() - 1;
        int x1 = origin.getX() + size.getX(), y1 = origin.getY() + size.getY(), z1 = origin.getZ() + size.getZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int edges = (x == x0 || x == x1 ? 1 : 0) + (y == y0 || y == y1 ? 1 : 0) + (z == z0 || z == z1 ? 1 : 0);
                    if (edges >= 2) level.setBlock(p.set(x, y, z), OUTLINE, QUIET);
                }
            }
        }
    }
}
