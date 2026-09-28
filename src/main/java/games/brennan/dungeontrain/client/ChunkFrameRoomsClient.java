package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.ChunkFrameRoomsRequestPacket;
import games.brennan.dungeontrain.net.ChunkFrameRoomsSyncPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client's copy of each frame's chunk-dimension selection, for the Chunk dimensions screen. The
 * screen asks for a refresh (throttled); the server also pushes one after every edit.
 */
public final class ChunkFrameRoomsClient {

    private static final long THROTTLE_MS = 1000L;

    private static final Map<String, ChunkFrameRoomsSyncPacket> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    private ChunkFrameRoomsClient() {}

    public static void accept(ChunkFrameRoomsSyncPacket packet) {
        SNAPSHOTS.put(packet.frame(), packet);
    }

    public static Optional<ChunkFrameRoomsSyncPacket> snapshot(String frame) {
        return Optional.ofNullable(SNAPSHOTS.get(frame));
    }

    public static void requestThrottled(String frame) {
        long now = System.currentTimeMillis();
        Long last = LAST_REQUEST.get(frame);
        if (last != null && now - last < THROTTLE_MS) return;
        LAST_REQUEST.put(frame, now);
        DungeonTrainNet.sendToServer(new ChunkFrameRoomsRequestPacket(frame));
    }

    public static void clear() {
        SNAPSHOTS.clear();
        LAST_REQUEST.clear();
    }
}
