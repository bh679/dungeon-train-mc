package games.brennan.dungeontrain.client.portal;

import games.brennan.dungeontrain.net.PortalTwinBoxesPacket;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Client-side cache of where the standing twin corridors are, fed by {@link PortalTwinBoxesPacket}.
 *
 * <p>Read by {@link ClientShulkerBoxPrediction} alone, and for one purpose: to decline predicting a
 * shulker box into a twin, so a box the server is about to refuse never appears. Nothing here is
 * authoritative — the server refuses independently in
 * {@code games.brennan.dungeontrain.event.PortalShulkerBoxEvents}, and a stale or missing cache costs
 * a flicker rather than a wrong answer.</p>
 *
 * <p>Unlike its neighbours in this package it holds a <b>box</b> and is happy to, for the reason
 * {@link games.brennan.dungeontrain.client.ClientPortalCrossing} sets out in the negative: a box is
 * only expensive when the thing it describes moves. A twin does not — it is stamped into the ground
 * and stands until its pair retires — so the server sends this only when the set actually changes.</p>
 *
 * <p>Cleared on logout by {@link games.brennan.dungeontrain.client.PortalRoomFogEvents}, with the
 * other portal caches and for the same reason: a twin from the previous world describes a place that
 * is not there any more.</p>
 */
public final class ClientPortalTwinBoxes {

    /** Volatile: written from the network thread, read on the client thread. */
    private static volatile List<PortalTwinBoxesPacket.Entry> boxes = List.of();

    private ClientPortalTwinBoxes() {}

    public static void applySnapshot(List<PortalTwinBoxesPacket.Entry> entries) {
        boxes = List.copyOf(entries);
    }

    /** Forget everything. Wired to logging out, so a twin never leaks into the next world. */
    public static void reset() {
        boxes = List.of();
    }

    /** Is this cell inside a standing twin corridor? */
    public static boolean contains(BlockPos pos) {
        List<PortalTwinBoxesPacket.Entry> current = boxes;
        if (current.isEmpty()) return false;
        for (PortalTwinBoxesPacket.Entry box : current) {
            if (box.contains(pos.getX(), pos.getY(), pos.getZ())) return true;
        }
        return false;
    }
}
