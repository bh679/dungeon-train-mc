package games.brennan.dungeontrain.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class CinematographerService {

    public static final double DEFAULT_DISTANCE = 6.0;

    private record State(double distance, GameType previousMode) {}

    private static final Map<UUID, State> ACTIVE = new HashMap<>();

    private CinematographerService() {}

    public static void enter(ServerPlayer player, double distance) {
        GameType current = player.gameMode.getGameModeForPlayer();
        ACTIVE.put(player.getUUID(), new State(distance, current));
        player.setGameMode(GameType.SPECTATOR);
    }

    public static void updateDistance(UUID playerId, double distance) {
        State existing = ACTIVE.get(playerId);
        if (existing != null) {
            ACTIVE.put(playerId, new State(distance, existing.previousMode()));
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
}
