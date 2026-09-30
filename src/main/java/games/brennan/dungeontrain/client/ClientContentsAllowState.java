package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.ContentsAllowRequestPacket;
import games.brennan.dungeontrain.net.ContentsAllowSyncPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client's copy of each carriage's / portal room's effective Contents allow-list, for the
 * Contents toggle screen. The screen asks for a refresh (throttled); the server also pushes one after
 * every toggle. Same shape as {@link ChunkFrameRoomsClient}.
 */
public final class ClientContentsAllowState {

    private static final long THROTTLE_MS = 1000L;

    private static final Map<String, ContentsAllowSyncPacket> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    private ClientContentsAllowState() {}

    private static String key(String kind, String target) {
        return kind + ":" + target;
    }

    public static void accept(ContentsAllowSyncPacket packet) {
        SNAPSHOTS.put(key(packet.kind(), packet.target()), packet);
    }

    public static Optional<ContentsAllowSyncPacket> snapshot(String kind, String target) {
        return Optional.ofNullable(SNAPSHOTS.get(key(kind, target)));
    }

    public static void requestThrottled(String kind, String target) {
        String k = key(kind, target);
        long now = System.currentTimeMillis();
        Long last = LAST_REQUEST.get(k);
        if (last != null && now - last < THROTTLE_MS) return;
        LAST_REQUEST.put(k, now);
        DungeonTrainNet.sendToServer(new ContentsAllowRequestPacket(kind, target));
    }

    public static void clear() {
        SNAPSHOTS.clear();
        LAST_REQUEST.clear();
    }
}
