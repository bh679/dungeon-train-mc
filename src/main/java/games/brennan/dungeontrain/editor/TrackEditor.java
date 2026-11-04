package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;

/**
 * Editor plots for the open-air track tile: the static entry points the commands and packets
 * still call, each a thin delegate to {@link TrackSideEditors#tile()}. The lifecycle itself lives
 * in {@link PlotEditor}; new code should call the instance.
 */
public final class TrackEditor {

    private TrackEditor() {}

    private static TrackSidePlotEditor<Template.Track> editor() {
        return TrackSideEditors.tile();
    }

    /** Plot origin for the synthetic-default variant. */
    public static BlockPos plotOrigin(CarriageDims dims) {
        return TrackSidePlots.plotOriginDefault(TrackKind.TILE, dims);
    }

    /** Plot origin for {@code (TILE, name)}. Used by the new/remove command. */
    public static BlockPos plotOrigin(String name, CarriageDims dims) {
        return TrackSidePlots.plotOrigin(TrackKind.TILE, name, dims);
    }

    /** True if {@code pos} sits inside any registered track-tile variant's plot. */
    public static boolean plotContaining(BlockPos pos, CarriageDims dims) {
        return resolveName(pos, dims) != null;
    }

    /** Resolved variant name for the plot {@code pos} sits inside, or null if outside every track-tile plot. */
    public static String resolveName(BlockPos pos, CarriageDims dims) {
        return editor().plotContaining(pos, dims).map(Template.Track::name).orElse(null);
    }

    /** Teleport {@code player} to the default-variant plot, restamping the row first. */
    public static void enter(ServerPlayer player) {
        enter(player, true);
    }

    /** Always restamps — see {@link PlotEditor#enter(ServerPlayer, Template, boolean)}. */
    public static void enter(ServerPlayer player, boolean onTop) {
        editor().enter(player, new Template.Track(), onTop);
    }

    /** Go here / the panel's Enter: a walk to the default tile plot, not a reload. */
    public static void walkTo(ServerPlayer player, boolean onTop) {
        editor().walkTo(player, new Template.Track(), onTop);
    }

    /** @param stamp whether to erase + restamp the plots before teleporting. */
    public static void enter(ServerPlayer player, boolean onTop, boolean stamp) {
        editor().enter(player, new Template.Track(), onTop, stamp);
    }

    /** Erase + restamp every registered track-tile variant plot. Idempotent. */
    public static void stampPlot(ServerLevel overworld, CarriageDims dims) {
        editor().stampAll(overworld, dims);
    }

    /** Erase + restamp the single plot for {@code name}. */
    public static void stampPlot(ServerLevel overworld, String name, CarriageDims dims) {
        editor().stampPlot(overworld, new Template.Track(name), dims);
    }

    /** Erase every track-tile plot — footprint + outline cage cleared to air. */
    public static void clearPlot(ServerLevel overworld, CarriageDims dims) {
        editor().clearAll(overworld, dims);
    }

    /** Erase a single named track-tile plot. */
    public static void clearPlot(ServerLevel overworld, String name, CarriageDims dims) {
        editor().clearPlot(overworld, new Template.Track(name), dims);
    }

    /** Snapshot key shared by {@link EditorDirtyCheck} for track-tile rows. */
    public static String snapshotKey(String name) {
        return editor().snapshotKey(new Template.Track(name));
    }

    /** Save the plot the player is standing in. */
    public static SaveResult save(ServerPlayer player) throws IOException {
        return editor().save(player);
    }
}
