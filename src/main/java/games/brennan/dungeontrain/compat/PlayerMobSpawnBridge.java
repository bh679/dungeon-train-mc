package games.brennan.dungeontrain.compat;

import games.brennan.adventureitemnames.api.NameComposer;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.echo.RemoteEchoEncounters;
import games.brennan.dungeontrain.train.PlayerMobGroupSpawner;
import games.brennan.playermob.compat.PlayerMobSpawnHooks;
import games.brennan.playermob.compat.ReincarnationRecord;
import games.brennan.playermob.entity.PlayerMobEntity;
import org.slf4j.Logger;

/**
 * Bridges PlayerMob's spawn seam ({@link PlayerMobSpawnHooks}) into DungeonTrain:
 * <ul>
 *   <li>opens a remote-echo {@link RemoteEchoEncounters encounter journal} whenever a PlayerMob spawns
 *       as a <em>remote</em> echo (a player who died in another world);</li>
 *   <li>names the friend-pair companion that spawns beside a train PlayerMob. The companion skips
 *       {@code finalizeSpawn}, so Adventure Item Names' spawn mixin never names it the way it names
 *       its leader — we run the same {@link NameComposer#applyMobName} call (honouring AIN's own
 *       chance/category config) before the companion enters the world;</li>
 *   <li>gives that companion its leader's on-train setup — carriage-contents tag, persistence and DT
 *       difficulty gear ({@link PlayerMobGroupSpawner#onCompanionSpawned}) — since it never passes
 *       through {@code PlayerMobGroupSpawner#spawnPlayerMob}.</li>
 * </ul>
 *
 * <p>Mirrors {@link PlayerMobSocialBridge}: the hard reference to {@code PlayerMobSpawnHooks} lives
 * only inside {@link #install()}, so this class loads even when the seam is absent; the caller
 * ({@code DungeonTrain.commonSetup}) gates on {@code ModList.isLoaded} and catches {@link Throwable},
 * so a PlayerMob build predating the seam (≤ 0.45.0) degrades to "no encounter stories" rather than a
 * crash. Local echoes ({@code remote == false}) are ignored.</p>
 */
public final class PlayerMobSpawnBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PlayerMobSpawnBridge() {}

    /** Subscribe the encounter journal (remote echoes only) and companion setup + naming to PlayerMob's spawn seam. */
    public static void install() {
        PlayerMobSpawnHooks.install(new PlayerMobSpawnHooks.SpawnObserver() {
            @Override
            public void onEchoSpawned(PlayerMobEntity mob, ReincarnationRecord record, boolean remote) {
                if (remote) {
                    RemoteEchoEncounters.onRemoteEchoSpawned(mob, record);
                }
            }

            @Override
            public void onCompanionSpawned(PlayerMobEntity companion, PlayerMobEntity leader) {
                // Setup first, each step isolated, so a naming fault can't skip the gear/tag or vice versa.
                try {
                    PlayerMobGroupSpawner.onCompanionSpawned(companion, leader);
                } catch (Throwable t) {
                    LOGGER.warn("[DungeonTrain] PlayerMob companion: train setup failed", t);
                }
                try {
                    NameComposer.applyMobName(companion, companion.getRandom());
                } catch (Throwable t) {
                    LOGGER.warn("[DungeonTrain] PlayerMob companion: naming failed", t);
                }
            }
        });
    }
}
