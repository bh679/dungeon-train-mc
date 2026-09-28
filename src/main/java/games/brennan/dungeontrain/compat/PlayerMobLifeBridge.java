package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.player.PlayerLifeRecord;
import games.brennan.playermob.player.PlayerLifeStore;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Credits conduct <em>into</em> PlayerMob's per-life record — the only bridge in this package that
 * runs that way round. Every other one ({@link PlayerMobSocialBridge}, {@link PlayerMobSpawnBridge},
 * {@link FreePlayBridge}) installs an observer and waits to be told something; this one does the
 * telling.
 *
 * <p>PlayerMob distils a completed life into the two traits its echo is born with
 * ({@code PlayerLifeRecord.toTraits()}), so anything credited here changes how that player's echo
 * behaves when it comes back. The tally is a world {@code SavedData} and never leaves the machine,
 * which is why the callers do not gate on network consent.</p>
 *
 * <p>Unlike the seams in {@code playermob.compat}, {@code PlayerLifeStore} is ordinary public API
 * with no stability promise, so each entry point is guarded twice: {@code ModList.isLoaded} for a
 * missing mod, and {@code catch (Throwable)} for a build whose shape has moved underneath us. A
 * PlayerMob that no longer offers a signal must cost a player their credit, not their world.</p>
 */
public final class PlayerMobLifeBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Logged once per session — a broken seam would otherwise repeat on every block placed. */
    private static boolean warned = false;

    private PlayerMobLifeBridge() {}

    /**
     * Record one act of sabotage against a build somebody made by hand. PlayerMob weighs these
     * into Fight/Flight on a capped term of their own, so a few raise the player's next echo and
     * a demolition does not saturate it.
     */
    public static void creditSabotage(ServerPlayer player) {
        if (!ModList.get().isLoaded("playermob")) return;
        try {
            PlayerLifeStore.record(player, PlayerLifeRecord.Signal.SABOTAGE, 1.0F);
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LOGGER.warn("[DungeonTrain] Could not credit sabotage to PlayerMob — "
                        + "echoes will not read it this session.", t);
            }
        }
    }
}
