package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.playermob.compat.FreePlayQuery;
import net.minecraft.server.level.ServerPlayer;

/**
 * Bridges PlayerMob's {@link FreePlayQuery} seam to DungeonTrain's Free Play rule: each captured
 * life is tagged with the dier's Free Play state, and at spawn a life reincarnates only for a player
 * whose Free Play state matches — so Free Play ("creative") echoes stay quarantined to Free Play
 * runs and legit echoes to legit runs, instead of one polluting the other.
 *
 * <p>Mirrors {@link PlayerMobSpawnBridge}: the hard reference to {@code FreePlayQuery} lives only
 * inside {@link #install()}, so this class loads even when the seam is absent; the caller
 * ({@code DungeonTrain.commonSetup}) gates on {@code ModList.isLoaded} and catches {@link Throwable},
 * so a PlayerMob build predating the seam degrades to no matching (every echo spawns) rather than a
 * crash.</p>
 *
 * <p>Dev builds ({@code branch != main}) disable the match ({@link FreePlayQuery.Provider#enforceMatch()}
 * → {@code false}) so a developer in creative still sees every echo while testing.</p>
 */
public final class FreePlayBridge {

    private FreePlayBridge() {}

    /** Install the Free Play provider that drives reincarnation provenance tagging + matching. */
    public static void install() {
        FreePlayQuery.install(new FreePlayQuery.Provider() {
            @Override
            public boolean isFreePlay(ServerPlayer player) {
                return RunIntegrity.isCheated(player);
            }

            @Override
            public boolean enforceMatch() {
                return !DungeonTrain.isDevBuild();
            }
        });
    }
}
