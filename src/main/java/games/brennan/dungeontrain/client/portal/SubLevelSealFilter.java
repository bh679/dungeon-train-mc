package games.brennan.dungeontrain.client.portal;

import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import games.brennan.dungeontrain.client.ClientPortalSeal;

import java.util.ArrayList;
import java.util.List;

/**
 * The sub-levels still worth drawing under this frame's twin seal — the train's half of the cut
 * {@code FrustumPortalSealMixin} makes for the world.
 *
 * <p>Sable draws sub-levels in a pass of its own, culled against Veil's own frustum wrapper rather
 * than through {@code Frustum.isVisible}, so the world hook does not reach them. That matters in
 * exactly one place and it is the one the player notices: inside the upside-down band the mirror
 * clears the bedrock row, so a basement twin hangs under open void with no seal to hide behind and
 * the train drifts past overhead in full view.</p>
 *
 * <p><b>Free when nothing is sealed.</b> The overwhelmingly common answer is the list that came in,
 * returned untouched with no allocation; only a camera actually inside a twin pays for a copy, and
 * that copy is a handful of entries. New list rather than a filtered view, so nothing the renderer
 * hands over is mutated.</p>
 */
public final class SubLevelSealFilter {

    private SubLevelSealFilter() {}

    /**
     * {@code subLevels} minus any whose world-space bounds lie wholly beyond this frame's seal.
     *
     * <p>The bounds are Sable's own global box for the sub-level, which is the train where it
     * actually is in the world — the same space the seal plane is measured in. Wholly beyond, so a
     * train straddling the plane is still drawn: half a carriage is worse than a whole one, and a
     * sub-level that close to the seal is one the player is about to be swapped into.</p>
     */
    public static Iterable<ClientSubLevel> beyondSeal(Iterable<ClientSubLevel> subLevels) {
        if (!ClientPortalSeal.sealed() || subLevels == null) return subLevels;

        List<ClientSubLevel> kept = new ArrayList<>();
        for (ClientSubLevel subLevel : subLevels) {
            BoundingBox3dc bounds = subLevel.boundingBox();
            if (bounds != null && ClientPortalSeal.hides(bounds.minY(), bounds.maxY())) continue;
            kept.add(subLevel);
        }
        return kept;
    }
}
