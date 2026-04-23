package games.brennan.dungeontrain.editor;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shared per-player "return to previous location" state for both
 * {@link CarriageEditor} and {@link TunnelEditor}.
 *
 * <p>One session per player at a time. When a player enters any editor plot,
 * their current position + dimension + look angles are captured; on
 * {@code /dungeontrain editor exit} they teleport back. A player swapping
 * between a carriage plot and a tunnel plot keeps their original return
 * target — {@link #saveIfAbsent} is a no-op when a session already exists.</p>
 */
public final class EditorSessions {

    public record Session(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch) {}

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private EditorSessions() {}

    public static synchronized void saveIfAbsent(UUID playerId, Session session) {
        SESSIONS.putIfAbsent(playerId, session);
    }

    public static synchronized Session remove(UUID playerId) {
        return SESSIONS.remove(playerId);
    }
}
