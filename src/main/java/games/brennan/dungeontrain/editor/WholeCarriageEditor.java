package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriageGroupPlacer;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Optional;

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

    private static int halfStep(CarriageDims dims) {
        return WholeHalfTemplateStore.halfDims(dims).length() + EditorLayout.GAP;
    }

    /** Footprint of a plot of {@code kind}. */
    public static Vec3i plotSize(WholeKind kind, CarriageDims dims) {
        return switch (kind) {
            case ROOM -> new Vec3i(dims.length(), dims.height(), dims.width());
            case GROUP -> CarriageGroupPlacer.sizeOf(dims, groupSize());
            case HALF -> {
                CarriageDims half = WholeHalfTemplateStore.halfDims(dims);
                yield new Vec3i(half.length(), half.height(), half.width());
            }
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
            case HALF -> new BlockPos(FIRST_PLOT_X + index * halfStep(dims), PLOT_Y, EditorLayout.WHOLE_HALF_FIRST_Z);
        };
    }

    /** Origin of {@code room}'s plot, or null when it is not registered. */
    public static BlockPos roomPlotOrigin(WholeCarriage room, CarriageDims dims) {
        return plotOrigin(WholeKind.ROOM, room.id(), dims);
    }

    /** Origin of {@code group}'s plot, or null when it is not registered. */
    public static BlockPos groupPlotOrigin(CarriageGroup group, CarriageDims dims) {
        return plotOrigin(WholeKind.GROUP, group.id(), dims);
    }

    /** Origin of {@code half}'s plot, or null when it is not registered. */
    public static BlockPos halfPlotOrigin(WholeCarriage half, CarriageDims dims) {
        return plotOrigin(WholeKind.HALF, half.id(), dims);
    }

    /** Origin for any Whole template; null for other kinds or unregistered ids. */
    public static BlockPos plotOrigin(Template model, CarriageDims dims) {
        WholeKind kind = kindOf(model);
        return kind == null ? null : plotOrigin(kind, model.id(), dims);
    }

    /** The slot {@code id} holds in {@code kind}'s row, or null when it is not registered. */
    public static BlockPos plotOrigin(WholeKind kind, String id, CarriageDims dims) {
        int index = WholeTemplateNew.ids(kind).indexOf(id);
        return index < 0 ? null : slotOrigin(kind, index, dims);
    }

    /**
     * The whole kind {@code model} belongs to, or null for a template of another section. This is
     * the one place the three Whole records are told apart — everything else here switches on the
     * {@link WholeKind} it returns, which the compiler checks.
     */
    public static WholeKind kindOf(Template model) {
        return switch (model) {
            case Template.WholeCarriage w -> WholeKind.ROOM;
            case Template.CarriageGroup g -> WholeKind.GROUP;
            case Template.WholeHalf h -> WholeKind.HALF;
            default -> null;
        };
    }

    /**
     * The plot containing {@code pos} (footprint plus the 1-block cage margin and two blocks of
     * headroom), or null. Answers only while WHOLE is the resident category.
     */
    public static PlotLocation plotContaining(BlockPos pos, CarriageDims dims) {
        for (WholeKind kind : WholeKind.values()) {
            Optional<? extends Template> hit = WholePlotEditor.forKind(kind).plotContaining(pos, dims);
            if (hit.isPresent()) return new PlotLocation(kind, hit.get().id(), hit.get());
        }
        return null;
    }

    // ---- lifecycle: delegates to WholePlotEditor ------------------------------------------------

    /** Erase, place and cage {@code room}'s plot. Idempotent. */
    public static void stampRoomPlot(ServerLevel overworld, WholeCarriage room, CarriageDims dims) {
        WholePlotEditor.room().stampPlot(overworld, new Template.WholeCarriage(room), dims);
    }

    /** Erase, place and cage {@code group}'s plot. A group of the wrong length stamps empty. */
    public static void stampGroupPlot(ServerLevel overworld, CarriageGroup group, CarriageDims dims) {
        WholePlotEditor.group().stampPlot(overworld, new Template.CarriageGroup(group), dims);
    }

    /** Erase, place and cage {@code half}'s plot. */
    public static void stampHalfPlot(ServerLevel overworld, WholeCarriage half, CarriageDims dims) {
        WholePlotEditor.half().stampPlot(overworld, new Template.WholeHalf(half), dims);
    }

    /** Stamp whichever Whole plot {@code model} is (its own {@code restampPlot}); other kinds are ignored. */
    public static void stampPlot(ServerLevel overworld, Template model, CarriageDims dims) {
        if (kindOf(model) != null) model.restampPlot(overworld, dims);
    }

    /** Erase the plot and its cage. */
    public static void clearPlot(ServerLevel overworld, Template model, CarriageDims dims) {
        switch (model) {
            case Template.WholeCarriage w -> WholePlotEditor.room().clearPlot(overworld, w, dims);
            case Template.CarriageGroup g -> WholePlotEditor.group().clearPlot(overworld, g, dims);
            case Template.WholeHalf h -> WholePlotEditor.half().clearPlot(overworld, h, dims);
            default -> { }
        }
    }

    /** The dirty-check snapshot key for a Whole plot. */
    public static String snapshotKey(WholeKind kind, String id) {
        return EditorPlotSnapshots.key(categoryIdOf(kind), id);
    }

    /** The category id each kind's rows file under: {@code whole}, {@code whole_group}, {@code whole_half}. */
    public static String categoryIdOf(WholeKind kind) {
        return switch (kind) {
            case ROOM -> "whole";
            case GROUP -> "whole_group";
            case HALF -> "whole_half";
        };
    }

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
        model.walkTo(player, onTop);
    }

    /** The panel's Enter button — land inside, restamping unless already standing in the plot. */
    public static void enterInside(ServerPlayer player, Template model, EditorPlotArrival.Inside inside) {
        model.enterInside(player, inside);
    }

    /** Enter whichever Whole plot {@code model} is; other kinds are logged and ignored. */
    public static void enter(ServerPlayer player, Template model, boolean onTop, boolean stamp,
                             EditorPlotArrival.Inside inside) {
        switch (model) {
            case Template.WholeCarriage w -> WholePlotEditor.room().enter(player, w, onTop, stamp, inside);
            case Template.CarriageGroup g -> WholePlotEditor.group().enter(player, g, onTop, stamp, inside);
            case Template.WholeHalf h -> WholePlotEditor.half().enter(player, h, onTop, stamp, inside);
            default -> LOGGER.warn("[DungeonTrain] Whole editor enter: unknown template '{}'", model.id());
        }
    }

    /** Capture {@code room}'s plot and persist it (user tier, plus the source tree in dev mode). */
    public static SaveResult saveRoom(ServerPlayer player, WholeCarriage room) throws IOException {
        return WholePlotEditor.room().save(player, new Template.WholeCarriage(room));
    }

    /** Capture {@code half}'s plot and persist it. */
    public static SaveResult saveHalf(ServerPlayer player, WholeCarriage half) throws IOException {
        return WholePlotEditor.half().save(player, new Template.WholeHalf(half));
    }

    /** Capture {@code group}'s plot and persist it. */
    public static SaveResult saveGroup(ServerPlayer player, CarriageGroup group) throws IOException {
        return WholePlotEditor.group().save(player, new Template.CarriageGroup(group));
    }
}
