package games.brennan.dungeontrain.ship.sable;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.ReplayModRecordingProbe;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side record of which players are recording a replay right now, so Sable's movement
 * snapshots for them go over the ordered connection (which Replay Mod records) instead of Sable's
 * UDP pipeline (which it does not).
 *
 * <p>Fed by {@code net.ReplayRecordingStatePacket}, which the client sends whenever its
 * {@link ReplayModRecordingProbe} answer changes. That is what makes this work on a dedicated
 * server, where nothing server-side could otherwise know a client is recording. On the
 * integrated server the singleplayer owner is additionally read straight from the probe, so a
 * recording started before the first packet lands is covered from its first tick.</p>
 *
 * <p>Consulted by {@code mixin.SubLevelTrackingOrderedSnapshotMixin} once per tracked player per
 * tracking tick — a concurrent set lookup, nothing heavier. Entries clear on logout and server
 * stop so a stale flag can never pin a reconnecting player to the slower path.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ReplayRecordingPlayers {

    private static final Set<UUID> RECORDING = ConcurrentHashMap.newKeySet();

    private ReplayRecordingPlayers() {}

    /** Record the client's latest answer. Idempotent. */
    public static void set(UUID player, boolean recording) {
        if (recording) {
            RECORDING.add(player);
        } else {
            RECORDING.remove(player);
        }
    }

    /** True if {@code player} has told the server it is recording. */
    public static boolean isRecording(UUID player) {
        return RECORDING.contains(player);
    }

    /**
     * True if {@code player}'s Sable snapshots should take the ordered connection for a replay:
     * either the client reported it, or this is the singleplayer owner and the in-process probe
     * says so.
     */
    public static boolean isRecording(ServerPlayer player) {
        if (RECORDING.contains(player.getUUID())) return true;
        return player.server.isSingleplayer()
            && player.server.isSingleplayerOwner(player.getGameProfile())
            && ReplayModRecordingProbe.isRecordingLocally();
    }

    /** Number of players currently flagged — for logs and tests. */
    public static int count() {
        return RECORDING.size();
    }

    /** Forget everyone. */
    public static void clear() {
        RECORDING.clear();
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        RECORDING.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        RECORDING.clear();
    }
}
