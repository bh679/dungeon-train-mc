package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.echo.RemoteEchoEncounters;
import games.brennan.playermob.compat.PlayerMobSpawnHooks;
import games.brennan.playermob.compat.ReincarnationRecord;
import games.brennan.playermob.entity.PlayerMobEntity;

/**
 * Bridges PlayerMob's echo-spawn seam ({@link PlayerMobSpawnHooks}) to DungeonTrain's remote-echo
 * {@link RemoteEchoEncounters encounter journal} — opening a journal whenever a PlayerMob spawns as a
 * <em>remote</em> echo (a player who died in another world).
 *
 * <p>Mirrors {@link PlayerMobSocialBridge}: the hard reference to {@code PlayerMobSpawnHooks} lives
 * only inside {@link #install()}, so this class loads even when the seam is absent; the caller
 * ({@code DungeonTrain.commonSetup}) gates on {@code ModList.isLoaded} and catches {@link Throwable},
 * so a PlayerMob build predating the seam (≤ 0.45.0) degrades to "no encounter stories" rather than a
 * crash. Local echoes ({@code remote == false}) are ignored.</p>
 */
public final class PlayerMobSpawnBridge {

    private PlayerMobSpawnBridge() {}

    /** Subscribe the encounter journal to PlayerMob's echo-spawn seam (remote echoes only). */
    public static void install() {
        PlayerMobSpawnHooks.install(new PlayerMobSpawnHooks.SpawnObserver() {
            @Override
            public void onEchoSpawned(PlayerMobEntity mob, ReincarnationRecord record, boolean remote) {
                if (remote) {
                    RemoteEchoEncounters.onRemoteEchoSpawned(mob, record);
                }
            }
        });
    }
}
