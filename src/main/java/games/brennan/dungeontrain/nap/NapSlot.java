package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.player.DifficultyPartition;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;

/**
 * Which nap slot a run uses: Free Play and Survival keep separate naps, and so does each difficulty,
 * so a nap only wakes in a run of the same kind ({@link NapStore}).
 */
public final class NapSlot {

    private NapSlot() {}

    /** {@code "<survival|freeplay>-<difficulty>"}, e.g. {@code survival-hard}. */
    public static String key(boolean freePlay, Difficulty difficulty) {
        return (freePlay ? "freeplay" : "survival") + "-" + DifficultyPartition.keyFor(difficulty);
    }

    /**
     * The slot a brand-new world wakes from, read at server start, before anyone has joined: Free
     * Play when the world starts in creative or spectator, or the session is already Free Play.
     */
    public static String forNewWorld(MinecraftServer server) {
        GameType mode = server.getWorldData().getGameType();
        boolean freePlay = mode == GameType.CREATIVE || mode == GameType.SPECTATOR
            || RunIntegrity.isVisiblySessionFreePlay();
        return key(freePlay, server.getWorldData().getDifficulty());
    }

    /**
     * Whether {@code nap} belongs in {@code slot}: its own Free Play flag and difficulty name that slot,
     * so a nap file renamed into another slot is refused. A nap saved before the difficulty was recorded
     * (empty) is judged on its Free Play flag alone.
     */
    public static boolean matches(String slot, NapSnapshot nap) {
        String kind = (nap.cheated() ? "freeplay" : "survival") + "-";
        if (nap.difficulty().isEmpty()) return slot.startsWith(kind);
        Difficulty recorded = Difficulty.byName(nap.difficulty());
        return recorded != null && slot.equals(key(nap.cheated(), recorded));
    }
}
