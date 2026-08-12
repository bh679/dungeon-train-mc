package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;

/**
 * "Is this builder world holding a track-side template, and which one?"
 *
 * <p>Four places need that answer — the protection check, the bounds packet, the dirty check and
 * Save — and each of them would otherwise read {@code builderTrackKind} out of world data and parse
 * it themselves. Four copies of a parse is four chances for one of them to read {@code ""} as a
 * {@link TrackKind#TILE} and start comparing a tunnel against a track tile's footprint.</p>
 *
 * <p>Server-side by necessity: world data only exists there. The client learns the same facts from
 * {@code BuilderBoundsPacket}, which is built from these calls.</p>
 */
public final class BuilderTrackBuild {

    private BuilderTrackBuild() {}

    /** The track kind on the plot, or null when this world is holding a carriage build. */
    @Nullable
    public static TrackKind activeKind(ServerLevel level) {
        return level == null ? null : kindOf(DungeonTrainWorldData.get(level));
    }

    /** As {@link #activeKind(ServerLevel)}, when the caller already has the world data in hand. */
    @Nullable
    public static TrackKind kindOf(DungeonTrainWorldData data) {
        if (data == null) {
            return null;
        }
        String id = data.builderTrackKind();
        return id == null || id.isEmpty() ? null : TrackKind.fromId(id);
    }

    /** Whether this world is holding a track-side build. */
    public static boolean isActive(ServerLevel level) {
        return activeKind(level) != null;
    }

    /** The volume of the open track template, or null when there isn't one. */
    @Nullable
    public static BoundingBox activePlot(ServerLevel level) {
        TrackKind kind = activeKind(level);
        if (kind == null) {
            return null;
        }
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        return BuilderTrackPlot.volume(kind, dims);
    }
}
