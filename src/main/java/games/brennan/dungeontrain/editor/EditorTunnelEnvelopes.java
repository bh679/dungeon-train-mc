package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import games.brennan.dungeontrain.tunnel.TunnelTrainEnvelope;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * The train envelope of every tunnel plot, in world space — what the client washes red when an
 * author builds into it. See {@link TunnelTrainEnvelope} for the geometry.
 *
 * <p>Geometry, not a sweep, like {@link EditorDoorGhosts}: the boxes are a function of the plot grid
 * and the world's dims, so they are recomputed whenever the dedup key is compared. Which blocks
 * actually sit inside them is the client's question — it can read its own chunks, and doing so
 * there means placing or breaking a block needs no server round-trip to update the wash.</p>
 */
public final class EditorTunnelEnvelopes {

    private EditorTunnelEnvelopes() {}

    /** One envelope box per registered tunnel plot — both SECTION and PORTAL, every name. */
    public static List<BoundingBox> snapshot(CarriageDims dims) {
        List<BoundingBox> out = new ArrayList<>();
        for (TunnelVariant variant : TunnelVariant.values()) {
            for (String name : TrackVariantRegistry.namesFor(TunnelTemplateStore.tunnelKind(variant))) {
                BlockPos origin = TunnelEditor.plotOrigin(variant, name);
                if (origin == null) continue;
                out.add(TunnelTrainEnvelope.worldBox(origin, dims));
            }
        }
        return out;
    }

    /**
     * Dedup key for the per-player push — the boxes themselves. A new variant, a deletion or a
     * dims change moves it; a steady editor tick does not.
     */
    public static String key(List<BoundingBox> boxes) {
        StringBuilder sb = new StringBuilder();
        for (BoundingBox box : boxes) {
            sb.append(box.minX()).append(',').append(box.minY()).append(',').append(box.minZ())
              .append('/').append(box.maxX()).append(',').append(box.maxY()).append(',')
              .append(box.maxZ()).append(';');
        }
        return sb.toString();
    }
}
