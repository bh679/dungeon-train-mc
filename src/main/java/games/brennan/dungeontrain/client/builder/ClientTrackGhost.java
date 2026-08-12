package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Which track blocks are <em>not</em> the one being edited.
 *
 * <p>A track tile is a four-block slice of a corridor that runs three hundred blocks in both
 * directions, out of the same blocks, at the same height. Stamped, it is invisible — there is
 * nothing to tell the template from the scenery, and editing the wrong four blocks looks exactly
 * like editing the right ones. Ghosting the rest of the line makes the plot the only solid thing in
 * the corridor while leaving the line there to judge the seam against.</p>
 *
 * <p>Split out from both the mixin that hides these blocks and the renderer that redraws them,
 * because the two must agree exactly: a block hidden but not redrawn is a hole, and one redrawn but
 * not hidden is a bright smear over a solid block. One predicate, asked twice.</p>
 *
 * <p><b>This runs per block during chunk meshing</b>, so the common case — no track build open —
 * has to cost one field read. {@link #active} is that read, and everything else is behind it.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ClientTrackGhost {

    /**
     * Whether a track template is open at all.
     *
     * <p>Mirrors {@code BuilderBoundsState.trackKindId()} rather than reading it, so the meshing
     * hot path never touches a string. Written from the bounds packet, read from the render and
     * meshing threads, hence volatile.</p>
     */
    private static volatile boolean active;

    /** The plot's box, or null when no track build is open. Replaced wholesale, never mutated. */
    private static volatile BoundingBox plot;

    private ClientTrackGhost() {}

    /**
     * Re-read the state from the bounds the server just sent.
     *
     * @return true if this changed what is ghosted — the caller re-meshes on that, because ghosting
     *         is baked into the chunk geometry and a stale mesh would keep the old line hidden
     */
    public static boolean update(String trackKindId, List<BoundingBox> volumes) {
        boolean nowActive = trackKindId != null && !trackKindId.isEmpty() && !volumes.isEmpty();
        BoundingBox nowPlot = nowActive ? volumes.get(0) : null;
        boolean changed = nowActive != active || !sameBox(nowPlot, plot);
        active = nowActive;
        plot = nowPlot;
        return changed;
    }

    /** Forget everything. The next world may not be a builder world at all. */
    public static void clear() {
        active = false;
        plot = null;
    }

    /** Whether any block is currently ghosted — the cheap gate every caller leads with. */
    public static boolean isActive() {
        return active;
    }

    /**
     * Whether {@code pos} is corridor track outside the open plot.
     *
     * <p>Only the bed and rail rows: the platform you stand on is scenery in every mode and
     * ghosting it would take the floor out from under the builder. The plot itself is excluded
     * last, because it is the one stretch of corridor that <em>is</em> the build.</p>
     *
     * <p>Corridor width comes from {@link CarriageDims#DEFAULT} — the client is not told the
     * world's dims, and this is the same approximation {@link OutOfBoundsWashRenderer} already
     * makes. A non-default world would ghost a corridor of the wrong width; cosmetic, and it fails
     * towards ghosting too little rather than hiding the build.</p>
     */
    public static boolean isGhosted(BlockPos pos) {
        return active && ghosts(true, plot, pos);
    }

    /**
     * The rule itself, with the state passed in so it can be tested without a client.
     *
     * <p>Package-private and static for that reason alone — {@link #isGhosted} is the call site
     * everything else uses.</p>
     */
    static boolean ghosts(boolean isActive, BoundingBox openPlot, BlockPos pos) {
        if (!isActive || pos == null) {
            return false;
        }
        int y = pos.getY();
        if (y != BuilderWorldLayout.Y_TRACK_BED && y != BuilderWorldLayout.Y_TRACK_RAIL) {
            return false;
        }
        if (!BuilderWorldLayout.inCorridor(pos.getZ(), CarriageDims.DEFAULT)
                || !BuilderWorldLayout.inPlatform(pos.getX(), pos.getZ())) {
            return false;
        }
        return openPlot == null || !openPlot.isInside(pos);
    }

    /** The open plot, or null. For the renderer's sweep, which walks outward from it. */
    public static BoundingBox plot() {
        return plot;
    }

    /** Null-tolerant box equality — {@link BoundingBox} has no equals worth relying on here. */
    private static boolean sameBox(BoundingBox a, BoundingBox b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.minX() == b.minX() && a.minY() == b.minY() && a.minZ() == b.minZ()
                && a.maxX() == b.maxX() && a.maxY() == b.maxY() && a.maxZ() == b.maxZ();
    }

}
