package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.track.variant.TrackKind;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * What the client knows about the track template currently open: whether there is one, which kind,
 * and where its plot is.
 *
 * <p>Small enough to look redundant beside {@link BuilderBoundsState}, and separate for one reason:
 * this is the answer to "what should be ghosted", and that question has one owner. The renderer asks
 * it; nothing has to re-derive a {@link TrackKind} from a string or guess which of the volumes is
 * the plot.</p>
 *
 * <p>What it no longer holds is a per-block predicate. There was one while the corridor was still
 * real and a mixin hid it block by block; now the corridor is erased on the way into a track build
 * and the surroundings are drawn from {@code BuilderTrackGhostShape}, so there is nothing to test
 * per block and no mixin to test it for.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ClientTrackGhost {

    /** The kind on the plot, or null when this world isn't holding a track build. */
    private static volatile TrackKind kind;

    /** The plot's box, or null likewise. Replaced wholesale, never mutated. */
    private static volatile BoundingBox plot;

    private ClientTrackGhost() {}

    /** Re-read the state from the bounds the server just sent. */
    public static void update(String trackKindId, List<BoundingBox> volumes) {
        TrackKind nowKind = trackKindId == null || trackKindId.isEmpty()
                ? null
                : TrackKind.fromId(trackKindId);
        kind = volumes.isEmpty() ? null : nowKind;
        plot = kind == null ? null : volumes.get(0);
    }

    /** Forget everything. The next world may not be a builder world at all. */
    public static void clear() {
        kind = null;
        plot = null;
    }

    /** Whether a track template is open — the gate every caller leads with. */
    public static boolean isActive() {
        return kind != null && plot != null;
    }

    /** The open kind, or null. */
    public static TrackKind kind() {
        return kind;
    }

    /** The open plot, or null. */
    public static BoundingBox plot() {
        return plot;
    }
}
