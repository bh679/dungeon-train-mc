package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TunnelTemplateId;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.Optional;

/**
 * Editor plots for the two tunnel kinds: the static entry points the commands and packets still
 * call, each a thin delegate to {@link TrackSideEditors#tunnel}. The lifecycle lives in
 * {@link PlotEditor}; new code should call the instance.
 *
 * <p>Tunnel dims are fixed, so the world's {@link CarriageDims} only feed the layout lookup; the
 * origins here are computed with the minimum dims, which give the same answer.</p>
 */
public final class TunnelEditor {

    /** Resolved variant + name pair for a player position. */
    public record TunnelPlot(TunnelVariant variant, String name) {}

    private TunnelEditor() {}

    private static TrackSidePlotEditor<Template.Tunnel> editor(TunnelVariant variant) {
        return TrackSideEditors.tunnel(variant);
    }

    private static CarriageDims fixedDims() {
        return CarriageDims.clamp(CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH, CarriageDims.MIN_HEIGHT);
    }

    /** Plot origin for {@code (variant, default)}. */
    public static BlockPos plotOrigin(TunnelVariant variant) {
        return plotOrigin(variant, TrackKind.DEFAULT_NAME);
    }

    /** Plot origin for an id-record-shaped tunnel template. */
    public static BlockPos plotOrigin(TunnelTemplateId id) {
        return plotOrigin(id.variant(), id.name());
    }

    /** Plot origin for {@code (variant, name)}. */
    public static BlockPos plotOrigin(TunnelVariant variant, String name) {
        return TrackSidePlots.plotOrigin(TunnelTemplateStore.tunnelKind(variant), name, fixedDims());
    }

    /** Returns the variant whose plot contains {@code pos}, or null. */
    public static TunnelVariant plotContaining(BlockPos pos) {
        TunnelPlot loc = plotContainingNamed(pos);
        return loc != null ? loc.variant() : null;
    }

    /** Returns the {@code (variant, name)} pair whose plot contains {@code pos}, or null. */
    public static TunnelPlot plotContainingNamed(BlockPos pos) {
        for (TunnelVariant variant : TunnelVariant.values()) {
            Optional<Template.Tunnel> hit = editor(variant).plotContaining(pos, fixedDims());
            if (hit.isPresent()) return new TunnelPlot(variant, hit.get().name());
        }
        return null;
    }

    /** Teleport to the default plot for {@code variant}, stamping every variant first. */
    public static void enter(ServerPlayer player, TunnelVariant variant) {
        enter(player, variant, true);
    }

    /** Always restamps — see {@link PlotEditor#enter(ServerPlayer, Template, boolean)}. */
    public static void enter(ServerPlayer player, TunnelVariant variant, boolean onTop) {
        editor(variant).enter(player, new Template.Tunnel(variant), onTop);
    }

    /** Go here / the panel's Enter: a walk to the variant's default plot, not a reload. */
    public static void walkTo(ServerPlayer player, TunnelVariant variant, boolean onTop) {
        editor(variant).walkTo(player, new Template.Tunnel(variant), onTop);
    }

    /** @param stamp whether to erase + restamp the plots before teleporting. */
    public static void enter(ServerPlayer player, TunnelVariant variant, boolean onTop, boolean stamp) {
        editor(variant).enter(player, new Template.Tunnel(variant), onTop, stamp);
    }

    /** Erase + restamp every registered variant for {@code variant}. Idempotent. */
    public static void stampPlot(ServerLevel overworld, TunnelVariant variant) {
        editor(variant).stampAll(overworld, fixedDims());
    }

    /** Erase + restamp the single plot for {@code (variant, name)}. */
    public static void stampPlot(ServerLevel overworld, TunnelVariant variant, String name) {
        editor(variant).stampPlot(overworld, new Template.Tunnel(variant, name), fixedDims());
    }

    /** Erase every variant plot for {@code variant}. */
    public static void clearPlot(ServerLevel overworld, TunnelVariant variant) {
        editor(variant).clearAll(overworld, fixedDims());
    }

    /** Erase a single named variant plot for {@code variant}. */
    public static void clearPlot(ServerLevel overworld, TunnelVariant variant, String name) {
        editor(variant).clearPlot(overworld, new Template.Tunnel(variant, name), fixedDims());
    }

    /** Snapshot key shared with {@link EditorDirtyCheck} for tunnel rows. */
    public static String tunnelSnapshotKey(TunnelVariant variant, String name) {
        return editor(variant).snapshotKey(new Template.Tunnel(variant, name));
    }

    /** Save the {@code variant} plot the player is standing in. */
    public static SaveResult save(ServerPlayer player, TunnelVariant variant) throws IOException {
        TunnelPlot loc = plotContainingNamed(player.blockPosition());
        if (loc == null || loc.variant() != variant) {
            throw new IOException("Player is not inside any tunnel_"
                + variant.name().toLowerCase(java.util.Locale.ROOT) + " plot.");
        }
        return save(player, new TunnelTemplateId(variant, loc.name()));
    }

    /** Save the explicitly-named tunnel variant; does not consult player position. */
    public static SaveResult save(ServerPlayer player, TunnelTemplateId id) throws IOException {
        return editor(id.variant()).save(player, new Template.Tunnel(id.variant(), id.name()));
    }
}
