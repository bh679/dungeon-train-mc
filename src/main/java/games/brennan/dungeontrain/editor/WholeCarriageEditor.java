package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.editor.relay.EditorRelaySave;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageDoorCells;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriageGroupPlacer;
import games.brennan.dungeontrain.train.CarriageGroupRegistry;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeCarriagePlacer;
import games.brennan.dungeontrain.train.WholeCarriageRegistry;
import games.brennan.dungeontrain.train.WholeKind;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

/**
 * The Whole section's plot editor — rooms ({@link WholeCarriage}) on one row and groups
 * ({@link CarriageGroup}) on the next.
 *
 * <p>Layout, all at {@link EditorLayout#PLOT_Y} from the shared origin:
 * <ul>
 *   <li>Room row at {@code Z = }{@link EditorLayout#WHOLE_ROOM_FIRST_Z}: one carriage box per
 *       room, stepping {@code length + GAP} along {@code +X} in registry order.</li>
 *   <li>Group row at {@code Z = }{@link EditorLayout#WHOLE_GROUP_FIRST_Z}: one
 *       {@code groupSize × length} box per group, stepping that plus {@code GAP} along {@code +X}.</li>
 * </ul>
 * The group row overlaps the parts grid's Z band in plan view; that is fine because only the
 * resident category ever answers {@link #plotContaining} (see {@link EditorLayout}).</p>
 *
 * <p>A group plot is as long as this world's {@link DungeonTrainConfig#getGroupSize() group size}.
 * A saved group holding a different count is refused by the store and its plot stamps empty; the
 * label says how many carriages it does hold so the author can see why.</p>
 */
public final class WholeCarriageEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PLOT_Y = EditorLayout.PLOT_Y;
    private static final int FIRST_PLOT_X = 0;

    /** Which plot a position resolves to, as the {@link Template} that owns it. */
    public record PlotLocation(WholeKind kind, String id, Template template) {}

    private WholeCarriageEditor() {}

    // ---- layout ---------------------------------------------------------------------------------

    /** This world's carriages per group — the length, in carriages, of every group plot. */
    public static int groupSize() {
        return Math.max(1, DungeonTrainConfig.getGroupSize());
    }

    private static int roomStep(CarriageDims dims) {
        return dims.length() + EditorLayout.GAP;
    }

    private static int groupStep(CarriageDims dims) {
        return groupSize() * dims.length() + EditorLayout.GAP;
    }

    /** Footprint of a plot of {@code kind}. */
    public static Vec3i plotSize(WholeKind kind, CarriageDims dims) {
        return switch (kind) {
            case ROOM -> new Vec3i(dims.length(), dims.height(), dims.width());
            case GROUP -> CarriageGroupPlacer.sizeOf(dims, groupSize());
        };
    }

    /** The slot-0 origin of {@code kind}'s row — what the row's type menu anchors on. */
    public static BlockPos rowOrigin(WholeKind kind, CarriageDims dims) {
        return slotOrigin(kind, 0, dims);
    }

    private static BlockPos slotOrigin(WholeKind kind, int index, CarriageDims dims) {
        return switch (kind) {
            case ROOM -> new BlockPos(FIRST_PLOT_X + index * roomStep(dims), PLOT_Y, EditorLayout.WHOLE_ROOM_FIRST_Z);
            case GROUP -> new BlockPos(FIRST_PLOT_X + index * groupStep(dims), PLOT_Y, EditorLayout.WHOLE_GROUP_FIRST_Z);
        };
    }

    /** Origin of {@code room}'s plot, or null when it is not registered. */
    public static BlockPos roomPlotOrigin(WholeCarriage room, CarriageDims dims) {
        int index = WholeCarriageRegistry.ids().indexOf(room.id());
        return index < 0 ? null : slotOrigin(WholeKind.ROOM, index, dims);
    }

    /** Origin of {@code group}'s plot, or null when it is not registered. */
    public static BlockPos groupPlotOrigin(CarriageGroup group, CarriageDims dims) {
        int index = CarriageGroupRegistry.ids().indexOf(group.id());
        return index < 0 ? null : slotOrigin(WholeKind.GROUP, index, dims);
    }

    /** Origin for any Whole template; null for other kinds or unregistered ids. */
    public static BlockPos plotOrigin(Template model, CarriageDims dims) {
        if (model instanceof Template.WholeCarriage w) return roomPlotOrigin(w.wholeCarriage(), dims);
        if (model instanceof Template.CarriageGroup g) return groupPlotOrigin(g.group(), dims);
        return null;
    }

    /**
     * The plot containing {@code pos} (footprint plus the 1-block cage margin and two blocks of
     * headroom), or null. Answers only while WHOLE is the resident category.
     */
    public static PlotLocation plotContaining(BlockPos pos, CarriageDims dims) {
        if (!EditorStampedCategoryState.isActive(EditorCategory.WHOLE)) return null;
        List<String> rooms = WholeCarriageRegistry.ids();
        Vec3i roomBox = plotSize(WholeKind.ROOM, dims);
        for (int i = 0; i < rooms.size(); i++) {
            if (inBox(pos, slotOrigin(WholeKind.ROOM, i, dims), roomBox)) {
                return new PlotLocation(WholeKind.ROOM, rooms.get(i),
                    new Template.WholeCarriage(new WholeCarriage(rooms.get(i))));
            }
        }
        List<String> groups = CarriageGroupRegistry.ids();
        Vec3i groupBox = plotSize(WholeKind.GROUP, dims);
        for (int i = 0; i < groups.size(); i++) {
            if (inBox(pos, slotOrigin(WholeKind.GROUP, i, dims), groupBox)) {
                return new PlotLocation(WholeKind.GROUP, groups.get(i),
                    new Template.CarriageGroup(new CarriageGroup(groups.get(i))));
            }
        }
        return null;
    }

    static boolean inBox(BlockPos pos, BlockPos o, Vec3i box) {
        return pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + box.getX()
            && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + box.getY() + 2
            && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + box.getZ();
    }

    // ---- stamp / clear --------------------------------------------------------------------------

    /** Erase, place and cage {@code room}'s plot. Idempotent. */
    public static void stampRoomPlot(ServerLevel overworld, WholeCarriage room, CarriageDims dims) {
        BlockPos origin = roomPlotOrigin(room, dims);
        if (origin == null) return;
        Vec3i box = plotSize(WholeKind.ROOM, dims);
        CarriagePlacer.eraseAt(overworld, origin, dims);
        EditorPlotEntityClearer.discardNonPlayersIn(overworld, origin, box);
        WholeCarriagePlacer.placeAt(overworld, origin, room, dims);
        EditorPlotCage.setOutline(overworld, origin, box, EditorPlotCage.OUTLINE_BLOCK);
        EditorPlotSnapshots.capture(snapshotKey(WholeKind.ROOM, room.id()),
            overworld, origin, box.getX(), box.getY(), box.getZ());
    }

    /** Erase, place and cage {@code group}'s plot. A group of the wrong length stamps empty. */
    public static void stampGroupPlot(ServerLevel overworld, CarriageGroup group, CarriageDims dims) {
        BlockPos origin = groupPlotOrigin(group, dims);
        if (origin == null) return;
        int n = groupSize();
        Vec3i box = plotSize(WholeKind.GROUP, dims);
        CarriageGroupPlacer.eraseAt(overworld, origin, dims, n);
        EditorPlotEntityClearer.discardNonPlayersIn(overworld, origin, box);
        CarriageGroupPlacer.placeAt(overworld, origin, group, dims, n);
        EditorPlotCage.setOutline(overworld, origin, box, EditorPlotCage.OUTLINE_BLOCK);
        EditorPlotSnapshots.capture(snapshotKey(WholeKind.GROUP, group.id()),
            overworld, origin, box.getX(), box.getY(), box.getZ());
    }

    /** Stamp whichever Whole plot {@code model} is; other kinds are ignored. */
    public static void stampPlot(ServerLevel overworld, Template model, CarriageDims dims) {
        if (model instanceof Template.WholeCarriage w) stampRoomPlot(overworld, w.wholeCarriage(), dims);
        else if (model instanceof Template.CarriageGroup g) stampGroupPlot(overworld, g.group(), dims);
    }

    /** Erase the plot and its cage. */
    public static void clearPlot(ServerLevel overworld, Template model, CarriageDims dims) {
        BlockPos origin = plotOrigin(model, dims);
        if (origin == null) return;
        WholeKind kind = model instanceof Template.CarriageGroup ? WholeKind.GROUP : WholeKind.ROOM;
        Vec3i box = plotSize(kind, dims);
        if (kind == WholeKind.GROUP) CarriageGroupPlacer.eraseAt(overworld, origin, dims, groupSize());
        else CarriagePlacer.eraseAt(overworld, origin, dims);
        EditorPlotCage.setOutline(overworld, origin, box, Blocks.AIR.defaultBlockState());
        EditorPlotSnapshots.clear(snapshotKey(kind, model.id()));
    }

    /** The dirty-check snapshot key for a Whole plot. */
    public static String snapshotKey(WholeKind kind, String id) {
        return EditorPlotSnapshots.key(kind == WholeKind.GROUP ? "whole_group" : "whole", id);
    }

    // ---- enter ----------------------------------------------------------------------------------

    /** Teleport to {@code room}'s plot, restamping first when {@code stamp}. */
    public static void enterRoom(ServerPlayer player, WholeCarriage room, boolean onTop, boolean stamp,
                                 EditorPlotArrival.Inside inside) {
        enter(player, new Template.WholeCarriage(room), onTop, stamp, inside);
    }

    /** Teleport to {@code group}'s plot, restamping first when {@code stamp}. */
    public static void enterGroup(ServerPlayer player, CarriageGroup group, boolean onTop, boolean stamp,
                                  EditorPlotArrival.Inside inside) {
        enter(player, new Template.CarriageGroup(group), onTop, stamp, inside);
    }

    /** The X menu's Go here — a walk, restamping only when the player is not already inside. */
    public static void walkTo(ServerPlayer player, Template model, boolean onTop) {
        enter(player, model, onTop, !EditorPlotScope.standingIn(player, model), EditorPlotArrival.Inside.FRONT_DOOR);
    }

    /** The panel's Enter button — land inside, restamping unless already standing in the plot. */
    public static void enterInside(ServerPlayer player, Template model, EditorPlotArrival.Inside inside) {
        enter(player, model, false, !EditorPlotScope.standingIn(player, model), inside);
    }

    public static void enter(ServerPlayer player, Template model, boolean onTop, boolean stamp,
                             EditorPlotArrival.Inside inside) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(model, dims);
        if (origin == null) {
            LOGGER.warn("[DungeonTrain] Whole editor enter: unknown template '{}'", model.id());
            return;
        }
        CarriageEditor.rememberReturn(player);
        if (stamp) stampPlot(overworld, model, dims);
        Vec3i footprint = model.plotSize(dims);
        BlockPos door = CarriageDoorCells.doorBases(origin, dims).get(0);
        EditorPlotArrival.land(player, overworld, origin, footprint, onTop, inside, door);
        LOGGER.info("[DungeonTrain] Whole editor enter: {} -> {} at {} ({})",
            player.getName().getString(), model.displayName(), origin, onTop ? "top" : "inside");
    }

    // ---- save -----------------------------------------------------------------------------------

    /** Capture {@code room}'s plot and persist it (user tier, plus the source tree in dev mode). */
    public static SaveResult saveRoom(ServerPlayer player, WholeCarriage room) throws IOException {
        ServerLevel overworld = overworldOf(player);
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = roomPlotOrigin(room, dims);
        if (origin == null) throw new IOException("Unknown whole room '" + room.id() + "'.");
        StructureTemplate template = WholeCarriagePlacer.captureTemplate(overworld, origin, dims);
        WholeCarriageTemplateStore.save(room, template);
        WholeCarriageRegistry.register(room);
        Vec3i box = plotSize(WholeKind.ROOM, dims);
        EditorPlotSnapshots.capture(snapshotKey(WholeKind.ROOM, room.id()),
            overworld, origin, box.getX(), box.getY(), box.getZ());
        EditorRelaySave.afterSave(player, new Template.WholeCarriage(room));
        LOGGER.info("[DungeonTrain] Whole editor save: {} -> room {}", player.getName().getString(), room.id());
        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            WholeCarriageTemplateStore.saveToSource(room, template);
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Whole editor save: source write failed for {}: {}", room.id(), e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    /** Capture {@code group}'s plot and persist it. */
    public static SaveResult saveGroup(ServerPlayer player, CarriageGroup group) throws IOException {
        ServerLevel overworld = overworldOf(player);
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = groupPlotOrigin(group, dims);
        if (origin == null) throw new IOException("Unknown whole group '" + group.id() + "'.");
        int n = groupSize();
        StructureTemplate template = CarriageGroupPlacer.captureTemplate(overworld, origin, dims, n);
        CarriageGroupTemplateStore.save(group, template);
        CarriageGroupRegistry.register(group);
        Vec3i box = plotSize(WholeKind.GROUP, dims);
        EditorPlotSnapshots.capture(snapshotKey(WholeKind.GROUP, group.id()),
            overworld, origin, box.getX(), box.getY(), box.getZ());
        EditorRelaySave.afterSave(player, new Template.CarriageGroup(group));
        LOGGER.info("[DungeonTrain] Whole editor save: {} -> group {} ({} carriages)",
            player.getName().getString(), group.id(), n);
        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            CarriageGroupTemplateStore.saveToSource(group, template);
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Whole editor save: source write failed for {}: {}", group.id(), e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    private static ServerLevel overworldOf(ServerPlayer player) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        return server.overworld();
    }
}
