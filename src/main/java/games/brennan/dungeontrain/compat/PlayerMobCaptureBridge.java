package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.playermob.compat.ReincarnationCaptureGate;
import net.minecraft.server.level.ServerPlayer;

/**
 * Bridges PlayerMob's reincarnation-capture seam ({@link ReincarnationCaptureGate}) to
 * DungeonTrain's Free Play rule: a death on a cheated / sandbox run must not enter the
 * reincarnation pool, so a consequence-free run never spawns local echoes of itself.
 *
 * <p>Mirrors {@link PlayerMobSpawnBridge}: the hard reference to {@code ReincarnationCaptureGate}
 * lives only inside {@link #install()}, so this class loads even when the seam is absent; the caller
 * ({@code DungeonTrain.commonSetup}) gates on {@code ModList.isLoaded} and catches {@link Throwable},
 * so a PlayerMob build predating the seam degrades to "Free Play deaths still captured" rather than a
 * crash.</p>
 *
 * <p>Dev builds ({@code branch != main}) are exempt — they always capture, matching the existing
 * "suppress on cheated runs except dev" idiom used for the public death feed
 * ({@code RunStatsEvents}). The predicate mirrors that condition exactly.</p>
 */
public final class PlayerMobCaptureBridge {

    private PlayerMobCaptureBridge() {}

    /** Veto reincarnation capture for Free Play (cheated) deaths, except on dev builds. */
    public static void install() {
        ReincarnationCaptureGate.install(new ReincarnationCaptureGate.CapturePredicate() {
            @Override
            public boolean shouldCapture(ServerPlayer player) {
                // Capture unless this is a Free Play (cheated) run — but dev builds always
                // capture, matching the death-feed idiom in RunStatsEvents.
                return !RunIntegrity.isCheated(player) || DungeonTrain.isDevBuild();
            }
        });
    }
}
