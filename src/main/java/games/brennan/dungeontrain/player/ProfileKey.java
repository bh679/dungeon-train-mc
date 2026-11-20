package games.brennan.dungeontrain.player;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.perks.PerksGate;
import net.minecraft.server.level.ServerPlayer;

/**
 * Which cross-world profile a player is on: their difficulty's ({@link DifficultyPartition}), or in
 * Test mode (a perks test world, {@link PerksGate#isTestWorld}) the test profile, which is kept apart
 * from every difficulty the same way the difficulties are kept apart from each other.
 */
public final class ProfileKey {

    private ProfileKey() {}

    /** The profile key for {@code player} right now: {@link PerksGate#TEST_PROFILE} in a test world, else the difficulty. */
    public static String current(ServerPlayer player) {
        return of(PerksGate.isTestWorld(), DifficultyPartition.keyFor(player.level()));
    }

    /** {@link #current} without a player, for tests. */
    static String of(boolean testWorld, String difficultyKey) {
        return testWorld ? PerksGate.TEST_PROFILE : difficultyKey;
    }

    /**
     * Are profiles kept apart right now? Always in a test world (the test profile never mixes with a
     * live one, whatever the config); otherwise per the {@code difficultyIsolatedStash} config.
     */
    public static boolean isolated() {
        return PerksGate.isTestWorld() || DungeonTrainConfig.getDifficultyIsolatedStash();
    }
}
