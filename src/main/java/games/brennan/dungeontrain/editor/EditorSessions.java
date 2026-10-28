package games.brennan.dungeontrain.editor;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Where each player was before they entered the editor, so {@code /dungeontrain editor exit} can
 * put them back: dimension, position, look angles and game mode, kept per player in RAM. Lost on
 * server restart, which is acceptable for an OP-only dev tool.
 *
 * <p>One map for every editor. The first entry is the anchor: re-entering a plot, or walking from a
 * carriage plot into a tunnel plot, keeps the position the player left the world from, and one exit
 * returns them there. Three editors used to keep their own maps, so a player who had entered
 * through more than one needed one exit per map.</p>
 */
public final class EditorSessions {

    public record Session(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch, GameType previousGameType) {}

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private EditorSessions() {}

    /**
     * Record {@code player}'s current dimension, position, look and game mode as the return-to
     * state, then switch them to creative for editing. No-op if the player already has a session.
     */
    public static void rememberReturn(ServerPlayer player) {
        if (SESSIONS.containsKey(player.getUUID())) return;
        GameType previous = player.gameMode.getGameModeForPlayer();
        SESSIONS.put(player.getUUID(), new Session(
            player.level().dimension(),
            player.position(),
            player.getYRot(),
            player.getXRot(),
            previous
        ));
        if (previous != GameType.CREATIVE) {
            player.setGameMode(GameType.CREATIVE);
        }
    }

    /** True when {@code player} entered the editor and has not exited. */
    public static boolean has(ServerPlayer player) {
        return SESSIONS.containsKey(player.getUUID());
    }

    /** Restore {@code player} to their pre-enter position, dimension and game mode. False if no session. */
    public static boolean exit(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session == null) return false;
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        ServerLevel dim = server.getLevel(session.dimension());
        if (dim == null) return false;
        player.teleportTo(dim, session.pos().x, session.pos().y, session.pos().z,
            session.yaw(), session.pitch());
        if (player.gameMode.getGameModeForPlayer() != session.previousGameType()) {
            player.setGameMode(session.previousGameType());
        }
        VariantOverlayRenderer.forget(player);
        return true;
    }
}
