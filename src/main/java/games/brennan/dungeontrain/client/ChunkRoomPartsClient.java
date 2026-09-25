package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.ChunkRoomPartsRequestPacket;
import games.brennan.dungeontrain.net.ChunkRoomPartsSyncPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client's copy of each dimensional carriage room's chunk parts, for the Chunk Parts screen.
 *
 * <p>Menus rebuild every frame, so the screen simply asks for a refresh (throttled) and shows what is
 * here; the server also pushes a fresh copy after every add or remove, so an edit shows on the next
 * frame rather than on the next throttle tick.</p>
 */
public final class ChunkRoomPartsClient {

    private static final long THROTTLE_MS = 1000L;

    private static final Map<String, ChunkRoomPartsSyncPacket> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    private ChunkRoomPartsClient() {}

    public static void accept(ChunkRoomPartsSyncPacket packet) {
        SNAPSHOTS.put(packet.room(), packet);
    }

    public static Optional<ChunkRoomPartsSyncPacket> snapshot(String room) {
        return Optional.ofNullable(SNAPSHOTS.get(room));
    }

    /** Ask the server for {@code room}'s parts, at most once a second. */
    public static void requestThrottled(String room) {
        long now = System.currentTimeMillis();
        Long last = LAST_REQUEST.get(room);
        if (last != null && now - last < THROTTLE_MS) return;
        LAST_REQUEST.put(room, now);
        DungeonTrainNet.sendToServer(new ChunkRoomPartsRequestPacket(room));
    }

    public static void clear() {
        SNAPSHOTS.clear();
        LAST_REQUEST.clear();
    }
}
