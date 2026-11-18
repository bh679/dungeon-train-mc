package games.brennan.dungeontrain.nap;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Whether anyone but the host has a saved player in this world. A LAN guest who has left keeps
 * their {@code playerdata/<uuid>.dat} here; a nap deletes the world, so it would take their gear
 * with it. While such a file exists the host gets no nap.
 */
public final class NapGuests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DAT = ".dat";

    private NapGuests() {}

    public static boolean othersSaved(MinecraftServer server, UUID owner) {
        try {
            return othersSaved(server.getWorldPath(LevelResource.PLAYER_DATA_DIR), owner);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Nap: could not locate playerdata; refusing", e);
            return true;
        }
    }

    /**
     * Whether any network connection besides the host's own is open: a LAN guest still logging in or
     * configuring, whom {@code getPlayerCount()} doesn't count yet. Checked on the server thread right
     * before a nap is saved; after the save {@code mixin/PlayerListNapWorldRefuseMixin} refuses new
     * logins, so no guest can get in between. A connection that can't be counted counts as yes.
     */
    public static boolean othersConnected(MinecraftServer server) {
        try {
            ServerConnectionListener listener = server.getConnection();
            return listener != null && othersConnected(listener.getConnections().size());
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Nap: could not count connections; refusing", e);
            return true;
        }
    }

    /** More than the host's own (in-memory) connection. */
    static boolean othersConnected(int connections) {
        return connections > 1;
    }

    /** Any {@code <uuid>.dat} for a uuid other than {@code owner}. Unreadable folder counts as yes. */
    static boolean othersSaved(Path playerdata, UUID owner) {
        if (!Files.isDirectory(playerdata)) return false;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(playerdata, "*" + DAT)) {
            for (Path file : files) {
                UUID id = uuidOf(file.getFileName().toString());
                if (id != null && !id.equals(owner)) return true;
            }
            return false;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Nap: could not list {}; refusing", playerdata, e);
            return true;
        }
    }

    private static UUID uuidOf(String name) {
        if (!name.endsWith(DAT)) return null;
        try {
            return UUID.fromString(name.substring(0, name.length() - DAT.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
