package games.brennan.dungeontrain.portal.tribute;

import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.player.DifficultyPartition;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Which of a player's tribute rooms a world shows: one per difficulty, and one for Free Play. A room
 * built on Hard is never stood on Easy, and a Free Play room (built with whatever Free Play allows)
 * stays among Free Play worlds. The key names the saved file ({@link TributeRoomStore}) and is what
 * the lottery draws within.
 */
public final class TributeRoomKeys {

    public static final String FREE_PLAY = "freeplay";

    private TributeRoomKeys() {}

    /**
     * The key for {@code player}'s run on {@code level}: Free Play when their run is, else the world's
     * difficulty. With no player (a room stood for the whole train) the world's own Free Play state decides.
     */
    public static String keyFor(ServerLevel level, @Nullable ServerPlayer player) {
        boolean freePlay = player != null ? RunIntegrity.isCheated(player) : RunIntegrity.isVisiblySessionFreePlay();
        return freePlay ? FREE_PLAY : DifficultyPartition.keyFor(level);
    }

    /** True for a key this class could have produced; a file name is never trusted further than that. */
    static boolean isKey(String key) {
        return key != null && !key.isEmpty() && key.chars().allMatch(c -> c >= 'a' && c <= 'z' || c == '_');
    }
}
