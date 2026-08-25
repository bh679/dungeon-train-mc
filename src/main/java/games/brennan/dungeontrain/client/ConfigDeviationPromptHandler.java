package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.AisDataIntegrity;
import games.brennan.dungeontrain.cheat.DtConfigIntegrity;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Surfaces {@link ConfigDeviationScreen} on the title screen when this install's governed configs
 * no longer match the ones Dungeon Train is balanced against.
 *
 * <p>Structure is the same one-shot {@link PoliticalFilterPromptHandler} uses: arm on
 * {@code ScreenEvent.Init.Post} for the title screen, open after a short tick delay, bail if the
 * player navigated away meanwhile so it never steals a click from someone already heading into a
 * world.</p>
 *
 * <p>The scan reads three small files and runs <b>once per session</b>, cached — the config can't
 * change underneath a running game in any way that matters, since both mods read theirs at launch.
 * The read is deliberately client-side and file-based: this has to answer before any world exists,
 * where the SERVER config spec is not loaded.</p>
 *
 * <p>Dismissal is per-deviation, not forever. "Keep my changes" stores a signature of exactly what
 * was different ({@link #signatureOf}); the prompt stays quiet while the config still matches it
 * and asks again if something else changes. A blanket "don't show again" would silently sign the
 * player up to every future edit, including ones they didn't make.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ConfigDeviationPromptHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Ticks to wait after the title screen initialises before opening. */
    private static final int OPEN_DELAY_TICKS = 24;

    /** Ticks remaining until the prompt opens; {@code -1} means "not armed". */
    private static int openDelayRemaining = -1;

    /** Title screen captured when the delay was armed — parent for the prompt, and the navigate-away check. */
    private static TitleScreen pendingParent = null;

    /**
     * Set once the prompt has been opened this session. The stored acknowledgement normally
     * suppresses a re-show on its own, but this covers the window between opening the screen and
     * the player answering it — without it, returning to the title screen with the prompt still
     * open would arm a second one.
     */
    private static boolean openedThisSession = false;

    /** Session cache of the scan: {@code null} until the first title screen. */
    private static List<String> deviations = null;

    private ConfigDeviationPromptHandler() {}

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (openDelayRemaining <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen)) {
            openDelayRemaining = -1;
            pendingParent = null;
            return;
        }
        openDelayRemaining--;
        if (openDelayRemaining != 0) return;

        TitleScreen parent = pendingParent;
        openDelayRemaining = -1;
        pendingParent = null;
        if (parent == null || mc.screen != parent || !shouldPrompt()) return;
        openedThisSession = true;
        LOGGER.info("[DungeonTrain] Config deviation: prompting at the title screen — {}",
            String.join(", ", deviations));
        mc.setScreen(new ConfigDeviationScreen(parent, deviations, signatureOf(deviations)));
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (openedThisSession || openDelayRemaining > 0) return;
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) return;
        if (!shouldPrompt()) return;
        openDelayRemaining = OPEN_DELAY_TICKS;
        pendingParent = titleScreen;
    }

    /** Is there a deviation the player hasn't already told us to keep? */
    private static boolean shouldPrompt() {
        if (deviations == null) deviations = scan();
        if (deviations.isEmpty()) return false;
        return !signatureOf(deviations).equals(ClientDisplayConfig.getConfigDeviationAcknowledged());
    }

    /** Every governed config's deviations, DT's own first, then AIS's. */
    private static List<String> scan() {
        List<String> found = new ArrayList<>();
        found.addAll(DtConfigIntegrity.check(FMLPaths.CONFIGDIR.get()));
        found.addAll(AisDataIntegrity.check(FMLPaths.CONFIGDIR.get()));
        return List.copyOf(found);
    }

    /**
     * A stable signature of one exact set of deviations. The deviation strings already carry both
     * the setting and its value, so joining them identifies the change precisely: put a value back
     * and the signature stops matching, change a different setting and it stops matching too.
     */
    static String signatureOf(List<String> deviations) {
        return String.join("|", deviations);
    }

    /** The player chose to keep their changes — remember this exact set and stop asking about it. */
    static void onKeptChanges(String signature) {
        ClientDisplayConfig.setConfigDeviationAcknowledged(signature);
        LOGGER.info("[DungeonTrain] Config deviation: player kept their changes");
    }

    /**
     * The player reset. The files are gone and defaults regenerate on next launch, so nothing is
     * left to prompt about this session; any stored acknowledgement is cleared, since it describes
     * a config that no longer exists.
     */
    static void onResetPerformed() {
        deviations = List.of();
        ClientDisplayConfig.setConfigDeviationAcknowledged("");
    }
}
