package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.TrackTemplate;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.TunnelTemplate;
import games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerPlayer;

/**
 * Resolves a player position to a track-side editor plot — the open-air
 * track tile, the three pillar sections, the stairs adjunct, or one of the
 * two tunnel kinds. Mirrors {@link CarriagePartEditor#plotContaining} for
 * the carriage-parts editor: returns the {@link TrackKind}, the name being
 * authored (currently always {@link TrackKind#DEFAULT_NAME} since the
 * single-plot editors haven't grown a multi-name UI yet), the plot origin,
 * and the footprint so the variant-overlay + shift-click handlers don't
 * need to know which physical editor backs which kind.
 *
 * <p>Lookup order matches {@link EditorCategory#locate} so the same
 * resolution wins no matter which entry point hits it: track tile first,
 * then pillar sections, then the stairs adjunct, then tunnels. Footprints
 * intentionally don't overlap (different X-rows + Z-rows in the editor
 * plot grid), so the order only matters for the cost of the first-match
 * short-circuit.</p>
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
     * {@code null} if they're outside every one. Uses the same 1-block
     * outline-margin contains-check the per-editor {@code plotContaining}
     * helpers use.
     */
    public static PlotInfo locate(ServerPlayer player, CarriageDims dims) {
        BlockPos pos = player.blockPosition();

        if (TrackEditor.plotContaining(pos, dims)) {
            return new PlotInfo(
                TrackKind.TILE,
                TrackKind.DEFAULT_NAME,
                TrackEditor.plotOrigin(),
                new Vec3i(TrackTemplate.TILE_LENGTH, TrackTemplate.HEIGHT, dims.width())
            );
        }

        PillarSection section = PillarEditor.plotContaining(pos, dims);
        if (section != null) {
            return new PlotInfo(
                pillarKind(section),
                TrackKind.DEFAULT_NAME,
                PillarEditor.plotOrigin(section),
                new Vec3i(1, section.height(), dims.width())
            );
        }

        PillarAdjunct adjunct = PillarEditor.plotContainingAdjunct(pos);
        if (adjunct != null) {
            return new PlotInfo(
                TrackKind.ADJUNCT_STAIRS,
                TrackKind.DEFAULT_NAME,
                PillarEditor.plotOriginAdjunct(adjunct),
                new Vec3i(adjunct.xSize(), adjunct.ySize(), adjunct.zSize())
            );
        }

        TunnelVariant tunnel = TunnelEditor.plotContaining(pos);
        if (tunnel != null) {
            return new PlotInfo(
                tunnelKind(tunnel),
                TrackKind.DEFAULT_NAME,
                TunnelEditor.plotOrigin(tunnel),
                new Vec3i(TunnelTemplate.LENGTH, TunnelTemplate.HEIGHT, TunnelTemplate.WIDTH)
            );
        }

        return null;
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
