package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Keeps Distant Horizons' own "update available" screen off the launch path for players running
 * the DH build the modpack pins.
 *
 * <p>DH's {@code MixinMinecraft} opens {@code UpdateModScreen} at startup whenever its self-updater
 * finds a newer release on Modrinth. The pack pins DH to one build on purpose — it is the build
 * whose API the band and portal-room suppression is compiled against — so for a pack player the
 * prompt is an invitation to break the pack. Any other DH version (a player who upgraded DH
 * themselves) is left alone and gets DH's prompt as normal.</p>
 *
 * <p>Matched by class name, so this file names no DH type and loads with or without DH present.
 * The swap happens in {@link ScreenEvent.Opening}: the update screen was going to return to a
 * fresh title screen on close, so that is what opens instead. Nothing of DH's is written or
 * changed — its updater config and state are untouched, and the prompt returns the moment the
 * player is on a DH build the pack does not pin.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DistantHorizonsUpdatePromptSuppression {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DH_MOD_ID = "distanthorizons";
    private static final String UPDATE_SCREEN_SUFFIX = "distanthorizons.common.wrappers.gui.updater.UpdateModScreen";

    private static boolean logged;

    private DistantHorizonsUpdatePromptSuppression() {}

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        if (next == null || !next.getClass().getName().endsWith(UPDATE_SCREEN_SUFFIX)) return;

        String pinned = VersionInfo.MODPACK_DISTANT_HORIZONS_VERSION;
        String loaded = loadedDhVersion();
        if (pinned.isEmpty() || loaded == null || !loaded.startsWith(pinned)) {
            if (!logged) {
                LOGGER.info("[DungeonTrain] Distant Horizons update prompt left alone: loaded {} vs modpack pin {}",
                        loaded, pinned.isEmpty() ? "(none)" : pinned);
                logged = true;
            }
            return;
        }

        if (!logged) {
            LOGGER.info("[DungeonTrain] Distant Horizons update prompt suppressed: loaded {} matches the modpack pin",
                    loaded);
            logged = true;
        }
        if (event.getCurrentScreen() instanceof TitleScreen) {
            event.setCanceled(true);
        } else {
            event.setNewScreen(new TitleScreen(false));
        }
    }

    private static String loadedDhVersion() {
        return ModList.get().getModContainerById(DH_MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse(null);
    }
}
