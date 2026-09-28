package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorMirrorPlotPacket;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps each editor player's client told which mirror-enabled plot they are standing in
 * ({@link EditorMirrorPlotPacket}), for client-side mirrored previews. Driven from
 * {@link VariantOverlayRenderer#onLevelTick}; deduped, so a steady tick sends nothing.
 *
 * <p>Resolved from where the player stands, like the X-menu mirror toggles — not per edited cell as
 * the server-side {@link EditorMirrorLiveHandler} does. Server thread only.</p>
 */
public final class EditorMirrorPlotSync {

    private static final Map<UUID, EditorMirrorPlotPacket> LAST = new HashMap<>();

    private EditorMirrorPlotSync() {}

    public static void push(ServerPlayer player, CarriageDims dims) {
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        EditorMirrorPlotPacket next = plot == null
            ? EditorMirrorPlotPacket.empty()
            : new EditorMirrorPlotPacket(plot.origin().immutable(), plot.footprint(),
                plot.mirrorX(), plot.mirrorY(), plot.mirrorZ());
        if (!next.active()) next = EditorMirrorPlotPacket.empty();
        EditorMirrorPlotPacket prev = LAST.get(player.getUUID());
        if (next.equals(prev) || (prev == null && !next.active())) return;
        LAST.put(player.getUUID(), next);
        DungeonTrainNet.sendTo(player, next);
    }

    /** Clear the client's copy once when the player leaves the build area. */
    public static void forget(ServerPlayer player) {
        EditorMirrorPlotPacket prev = LAST.remove(player.getUUID());
        if (prev != null && prev.active()) DungeonTrainNet.sendTo(player, EditorMirrorPlotPacket.empty());
    }

    public static void clearAll() {
        LAST.clear();
    }
}
