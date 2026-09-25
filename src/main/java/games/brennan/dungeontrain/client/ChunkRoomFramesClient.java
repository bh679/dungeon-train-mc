package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.ChunkRoomFramesRequestPacket;
import games.brennan.dungeontrain.net.ChunkRoomFramesSyncPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client's copy of each dimensional carriage room's frame list, for the Frames screen.
 *
 * <p>Menus rebuild every frame, so the screen simply asks for a refresh (throttled) and shows what is
 * here; the server also pushes a fresh copy after every add or remove, so an edit shows on the next
 * frame rather than on the next throttle tick.</p>
 */
public final class ChunkRoomFramesClient {

    private static final long THROTTLE_MS = 1000L;

    private static final Map<String, ChunkRoomFramesSyncPacket> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    private ChunkRoomFramesClient() {}

    public static void accept(ChunkRoomFramesSyncPacket packet) {
        SNAPSHOTS.put(packet.room(), packet);
    }

    public static Optional<ChunkRoomFramesSyncPacket> snapshot(String room) {
        return Optional.ofNullable(SNAPSHOTS.get(room));
    }

    /** Ask the server for {@code room}'s frames, at most once a second. */
    public static void requestThrottled(String room) {
        long now = System.currentTimeMillis();
        Long last = LAST_REQUEST.get(room);
        if (last != null && now - last < THROTTLE_MS) return;
        LAST_REQUEST.put(room, now);
        DungeonTrainNet.sendToServer(new ChunkRoomFramesRequestPacket(room));
    }

    public static void clear() {
        SNAPSHOTS.clear();
        LAST_REQUEST.clear();
    }
}
