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
        return key(freePlay, DifficultyPartition.keyFor(difficulty));
    }

    /**
     * The slot for a run on {@code profile}: a difficulty key, or {@link games.brennan.dungeontrain.perks.PerksGate#TEST_PROFILE} for Test
     * mode, which keeps its own naps ({@code survival-perktest}) the way each difficulty does.
     */
    public static String key(boolean freePlay, String profile) {
        return (freePlay ? "freeplay" : "survival") + "-" + profile;
    }

    /** The slot {@code player}'s run naps into right now: Test mode's own, or their difficulty's. */
    public static String keyFor(boolean freePlay, Difficulty difficulty, boolean testWorld) {
        return testWorld ? key(freePlay, games.brennan.dungeontrain.perks.PerksGate.TEST_PROFILE) : key(freePlay, difficulty);
    }

    /**
     * The slot a brand-new world wakes from, read at server start, before anyone has joined: Free
     * Play when the world starts in creative or spectator, or the session is already Free Play.
     */
    public static String forNewWorld(MinecraftServer server) {
        GameType mode = server.getWorldData().getGameType();
        boolean freePlay = mode == GameType.CREATIVE || mode == GameType.SPECTATOR
            || RunIntegrity.isVisiblySessionFreePlay();
        return keyFor(freePlay, server.getWorldData().getDifficulty(), games.brennan.dungeontrain.perks.PerksGate.isTestWorld());
    }

    /**
     * Whether {@code nap} belongs in {@code slot}: its own Free Play flag and difficulty name that slot,
     * so a nap file renamed into another slot is refused. A nap saved before the difficulty was recorded
     * (empty) is judged on its Free Play flag alone.
     */
    public static boolean matches(String slot, NapSnapshot nap) {
        return matches(slot, nap, true);
    }

    /**
     * {@link #matches}, with the Free Play/Survival half checked only when {@code checkKind}: a nap whose
     * file was changed outside the game reads as Free Play whatever slot it sat in, and wakes as Free Play.
     */
    public static boolean matches(String slot, NapSnapshot nap, boolean checkKind) {
        String kind = (nap.cheated() ? "freeplay" : "survival") + "-";
        // A Test mode nap (recorded on the test profile) wakes only in a test slot, and only a Test
        // mode nap wakes there: its advancements and stats must never reach a live run.
        boolean testNap = games.brennan.dungeontrain.perks.PerksGate.TEST_PROFILE.equals(nap.difficulty());
        if (testNap || slot.endsWith("-" + games.brennan.dungeontrain.perks.PerksGate.TEST_PROFILE)) {
            if (!testNap || !slot.endsWith("-" + games.brennan.dungeontrain.perks.PerksGate.TEST_PROFILE)) return false;
            return !checkKind || slot.equals(key(nap.cheated(), games.brennan.dungeontrain.perks.PerksGate.TEST_PROFILE));
        }
        if (nap.difficulty().isEmpty()) return !checkKind || slot.startsWith(kind);
        Difficulty recorded = Difficulty.byName(nap.difficulty());
        if (recorded == null) return false;
        return checkKind ? slot.equals(key(nap.cheated(), recorded)) : slot.endsWith("-" + DifficultyPartition.keyFor(recorded));
    }
}
