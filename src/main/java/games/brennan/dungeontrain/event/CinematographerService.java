package games.brennan.dungeontrain.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class CinematographerService {

    public static final double DEFAULT_DISTANCE = 50.0;

    /** Default clear-view reach is the door scan distance plus this offset. */
    public static final double CLEARVIEW_DISTANCE_OFFSET = 12.0;

    private record State(double distance, GameType previousMode, boolean clearView, double clearViewDistance) {}

    private static final Map<UUID, State> ACTIVE = new HashMap<>();

    private CinematographerService() {}

    public static void enter(ServerPlayer player, double distance) {
        GameType current = player.gameMode.getGameModeForPlayer();
        ACTIVE.put(player.getUUID(), new State(distance, current, false, distance + CLEARVIEW_DISTANCE_OFFSET));
        player.setGameMode(GameType.SPECTATOR);
    }

    public static void updateDistance(UUID playerId, double distance) {
        State existing = ACTIVE.get(playerId);
        if (existing != null) {
            ACTIVE.put(playerId, new State(distance, existing.previousMode(),
                existing.clearView(), existing.clearViewDistance()));
        }
    }

    public static void exit(ServerPlayer player) {
        State state = ACTIVE.remove(player.getUUID());
        if (state != null) {
            player.setGameMode(state.previousMode());
        }
    }

    public static void cleanup(UUID playerId) {
        ACTIVE.remove(playerId);
    }

    public static boolean isActive(UUID playerId) {
        return ACTIVE.containsKey(playerId);
    }

    public static double getDistance(UUID playerId) {
        State state = ACTIVE.get(playerId);
        return state != null ? state.distance() : DEFAULT_DISTANCE;
    }

    /** Toggle the clear-view sub-mode on/off, keeping the current reach. */
    public static void setClearView(UUID playerId, boolean enabled) {
        State existing = ACTIVE.get(playerId);
        if (existing != null) {
            ACTIVE.put(playerId, new State(existing.distance(), existing.previousMode(),
                enabled, existing.clearViewDistance()));
        }
    }

    /** Toggle the clear-view sub-mode and set its reach in one shot. */
    public static void setClearView(UUID playerId, boolean enabled, double clearViewDistance) {
        State existing = ACTIVE.get(playerId);
        if (existing != null) {
            ACTIVE.put(playerId, new State(existing.distance(), existing.previousMode(),
                enabled, clearViewDistance));
        }
    }

    public static boolean isClearView(UUID playerId) {
        State state = ACTIVE.get(playerId);
        return state != null && state.clearView();
    }

    public static double getClearViewDistance(UUID playerId) {
        State state = ACTIVE.get(playerId);
        return state != null ? state.clearViewDistance() : DEFAULT_DISTANCE + CLEARVIEW_DISTANCE_OFFSET;
    }

    /** The default clear-view reach for this player = current door distance + offset. */
    public static double getDefaultClearViewDistance(UUID playerId) {
        State state = ACTIVE.get(playerId);
        double door = state != null ? state.distance() : DEFAULT_DISTANCE;
        return door + CLEARVIEW_DISTANCE_OFFSET;
    }
}
