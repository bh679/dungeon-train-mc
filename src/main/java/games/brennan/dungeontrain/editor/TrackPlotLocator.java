package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerPlayer;

/**
 * Single-pass containment check that resolves a player position to the
 * exact track-side plot they're standing in — track tile, pillar section,
 * stairs adjunct, or tunnel kind — including the variant name. Delegates
 * to {@link TrackSidePlots#locate} for the full grid scan.
 */
public final class TrackPlotLocator {

    /**
     * Resolved plot — kind, name, world-space origin (the plot's min corner,
     * not the outline cage), and footprint dims used for the local-position
     * bounds check inside {@link VariantBlockInteractions} and
     * {@link VariantOverlayRenderer}.
     */
    public record PlotInfo(TrackKind kind, String name, BlockPos origin, Vec3i footprint) {}

    private TrackPlotLocator() {}

    /**
     * Resolve which track-side plot {@code player} is standing in, or
     * {@code null} if they're outside every one. Walks every registered
     * variant of every {@link TrackKind} via {@link TrackSidePlots#locate}.
     */
    public static PlotInfo locate(ServerPlayer player, CarriageDims dims) {
        return TrackSidePlots.locate(player.blockPosition(), dims);
    }

    /** Map a {@link PillarSection} to its {@link TrackKind}. */
    public static TrackKind pillarKind(PillarSection section) {
        return switch (section) {
            case TOP -> TrackKind.PILLAR_TOP;
            case MIDDLE -> TrackKind.PILLAR_MIDDLE;
            case BOTTOM -> TrackKind.PILLAR_BOTTOM;
        };
    }

    /** Map a {@link TunnelVariant} to its {@link TrackKind}. */
    public static TrackKind tunnelKind(TunnelVariant variant) {
        return switch (variant) {
            case SECTION -> TrackKind.TUNNEL_SECTION;
            case PORTAL -> TrackKind.TUNNEL_PORTAL;
        };
    }
}
