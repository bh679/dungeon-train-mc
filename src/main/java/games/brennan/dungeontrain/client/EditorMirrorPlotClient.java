package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.EditorMirrorPlotPacket;
import org.jetbrains.annotations.Nullable;

/**
 * Client copy of the mirror-enabled editor plot the player is standing in, from
 * {@link EditorMirrorPlotPacket}; {@code null} when there is none. Read by
 * {@link games.brennan.dungeontrain.compat.EffortlessBuildingPreviewMirror}. Holds only plain
 * data, so it is safe to reference from common code.
 */
public final class EditorMirrorPlotClient {

    @Nullable
    private static volatile EditorMirrorPlotPacket current;

    private EditorMirrorPlotClient() {}

    public static void set(@Nullable EditorMirrorPlotPacket plot) {
        current = plot;
    }

    public static @Nullable EditorMirrorPlotPacket get() {
        return current;
    }
}
