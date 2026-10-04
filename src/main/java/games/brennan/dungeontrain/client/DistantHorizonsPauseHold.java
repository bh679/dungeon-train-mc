package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import games.brennan.dungeontrain.worldgen.LodGenerationHold;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Holds <b>Distant Horizons'</b> LOD generation while a singleplayer game sits paused.
 *
 * <p>DH keeps generating behind the pause menu — its world-gen workers request chunks that the paused
 * integrated server still services — so a long break is spent burning CPU and heap on far terrain,
 * and the game comes back sluggish. Once the game has been paused for
 * {@link LodGenerationHold#GRACE_MILLIS}, this switches DH's {@code enableDistantWorldGeneration} off
 * through DH's API, and clears that override the moment the game resumes or the world is left.</p>
 *
 * <p><b>Nothing persists.</b> An API value overrides DH's setting in memory only; DH's config file is
 * never written, so a crash mid-pause cannot leave a player's distant generation off. DT only ever
 * clears an override it set itself, and never touches one another mod already holds or a setting the
 * player has switched off. DH stops and restarts its own generation queue on the change, so no
 * half-decorated LOD chunk is left behind.</p>
 *
 * <p><b>Loading.</b> Like {@link DistantHorizonsSuppression}, this names DH types and is reached
 * only behind the {@code ModList} check in {@link DungeonTrainClient}. Any DH failure degrades to
 * "DH keeps generating", logged once.</p>
 */
public final class DistantHorizonsPauseHold {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** When the current pause began, or -1 while the game runs. Client thread only. */
    private static long pausedSinceMillis = -1L;
    /** Whether DT currently owns DH's API override. Client thread only. */
    private static boolean ownsOverride;
    /** Set after the first DH failure — stop trying for the rest of the session. */
    private static boolean broken;

    private DistantHorizonsPauseHold() {}

    /** Bind the hold to the client tick. Call once, on the client, only when DH is loaded. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(DistantHorizonsPauseHold::onClientTick);
        NeoForge.EVENT_BUS.addListener(DistantHorizonsPauseHold::onLoggingOut);
        LodGenerationHold.markIdle();
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (broken) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        boolean paused = mc.hasSingleplayerServer() && mc.isPaused();
        if (!paused) {
            pausedSinceMillis = -1L;
            release(now);
            return;
        }
        if (pausedSinceMillis < 0) pausedSinceMillis = now;
        if (!ownsOverride && LodGenerationHold.shouldHold(pausedSinceMillis, now)) {
            hold(now);
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        pausedSinceMillis = -1L;
        release(System.currentTimeMillis());
    }

    private static void hold(long now) {
        try {
            IDhApiConfigValue<Boolean> setting = distantGenerationSetting();
            // Not initialised yet, or nothing to hold: another mod owns the override, or the
            // player has distant generation off. Re-checked next tick while still paused.
            if (setting == null || setting.getApiValue() != null || !Boolean.TRUE.equals(setting.getValue())) {
                return;
            }
            if (!setting.setValue(false)) {
                fail("Distant Horizons refused the world-generation override");
                return;
            }
            ownsOverride = true;
            LodGenerationHold.markHolding(now);
            LOGGER.info("[DungeonTrain] Game paused for {}s — holding Distant Horizons LOD generation until it resumes",
                    LodGenerationHold.GRACE_MILLIS / 1000L);
        } catch (Throwable t) {
            fail(t.toString());
        }
    }

    private static void release(long now) {
        if (!ownsOverride) return;
        long heldSeconds = LodGenerationHold.holdingSeconds(now);
        ownsOverride = false;
        LodGenerationHold.markIdle();
        try {
            IDhApiConfigValue<Boolean> setting = distantGenerationSetting();
            if (setting != null) setting.clearValue();
            LOGGER.info("[DungeonTrain] Game resumed — released Distant Horizons LOD generation after {}s held",
                    heldSeconds);
        } catch (Throwable t) {
            fail(t.toString());
        }
    }

    private static IDhApiConfigValue<Boolean> distantGenerationSetting() {
        IDhApiConfig configs = DhApi.Delayed.configs;
        return configs == null ? null : configs.worldGenerator().enableDistantWorldGeneration();
    }

    private static void fail(String reason) {
        broken = true;
        ownsOverride = false;
        LodGenerationHold.markUnavailable();
        LOGGER.warn("[DungeonTrain] Could not hold Distant Horizons LOD generation while paused; "
                + "it will keep generating behind the pause menu: {}", reason);
    }
}
