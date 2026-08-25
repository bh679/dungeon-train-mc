package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.track.variant.TrackKind;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Which family a {@link TrackKind} belongs to, for the ghost scene's sake.
 *
 * <p>{@link TrackKind} is a storage enum — eight flat constants naming directories — and the scene
 * cares about something it does not encode: whether a piece hangs off a column, sits in a tunnel
 * run, or is part of the line. Asking that question in one place keeps the three arms of the scene
 * builder from each spelling out their own list and drifting apart when a kind is added.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderTrackGhostShapes {

    private BuilderTrackGhostShapes() {}

    /** Bolts onto the side of a pillar and repeats downward to the ground. */
    static boolean isAdjunct(TrackKind kind) {
        return kind == TrackKind.ADJUNCT_STAIRS || kind == TrackKind.ADJUNCT_STAIRS_ENTRANCE;
    }

    /** Wraps the line, and is only ever seen as one piece of a run. */
    static boolean isTunnel(TrackKind kind) {
        return kind == TrackKind.TUNNEL_SECTION || kind == TrackKind.TUNNEL_PORTAL;
    }
}
