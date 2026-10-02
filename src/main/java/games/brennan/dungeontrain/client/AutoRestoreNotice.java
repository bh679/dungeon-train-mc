package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.data.AutoRestore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * Runs {@link AutoRestore} the first time the title screen is up, and tells the player when it put
 * something back.
 *
 * <p><b>At the title screen, before anything else.</b> The data has to be back before a world loads,
 * or the run has already started without it — and a world join rewrites the very progress files the
 * restore is about to put back. So the restore runs on the first idle title-screen tick, with no
 * delay: nothing the player can click gets them into a world sooner than that.</p>
 *
 * <p><b>The notice is a card, not a toast, and tick-driven.</b> A toast raised at the first title
 * screen expires behind the resource-loading overlay. Same shape as {@link DpiBypassPromptHandler}:
 * wait for the overlay to clear, a short delay, then open only if the player is still on that
 * title screen — so it never steals a click, and it waits its turn behind any other card.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class AutoRestoreNotice {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Ticks to wait after arming before opening — matches the sibling title-screen cards. */
    private static final int OPEN_DELAY_TICKS = 24;

    /** Set once the restore pass has run; it runs exactly once per session on the client. */
    private static boolean ran = false;

    /** What the pass did, while its notice is still waiting to be shown; null otherwise. */
    private static AutoRestore.Outcome pending = null;

    private static int openDelayRemaining = -1;
    private static TitleScreen pendingParent = null;

    private AutoRestoreNotice() {}

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (ran && pending == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen titleScreen) || mc.getOverlay() != null) {
            openDelayRemaining = -1;
            pendingParent = null;
            return;
        }

        if (!ran) {
            ran = true;
            AutoRestore.Outcome outcome = AutoRestore.runIfEnabled();
            if (outcome.restored()) pending = outcome;
            return;
        }

        if (openDelayRemaining < 0) {
            openDelayRemaining = OPEN_DELAY_TICKS;
            pendingParent = titleScreen;
            return;
        }

        openDelayRemaining--;
        if (openDelayRemaining > 0) return;

        TitleScreen parent = pendingParent;
        openDelayRemaining = -1;
        pendingParent = null;
        if (parent == null || mc.screen != parent) return;

        AutoRestore.Outcome outcome = pending;
        pending = null;
        LOGGER.info("[DungeonTrain] Automatic restore: telling the player ({} file(s))", outcome.files());
        mc.setScreen(new AutoRestoreNoticeScreen(parent, outcome.files()));
    }
}
