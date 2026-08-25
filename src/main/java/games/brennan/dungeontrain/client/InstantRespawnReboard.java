package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

/**
 * Makes vanilla's <b>Immediate Respawn</b> game rule ({@code doImmediateRespawn})
 * mean what it should mean in a new-world-per-run game: dying rolls straight into
 * a fresh world, with no death screen and no button to press.
 *
 * <p>Dungeon Train already ends the run on death — {@link NarrativeDeathScreen}
 * replaces the vanilla screen and its "Board anew" control creates the next world
 * via {@link DeathScreenLayoutHandler#launchWorld}. With {@code doImmediateRespawn}
 * on, vanilla never opens a death screen at all: {@code ClientPacketListener}
 * calls {@link LocalPlayer#respawn()} directly, which on a Dungeon Train world
 * dropped the player back onto the same train in the same world — the one flow
 * that quietly opted out of run-per-world. This class closes that gap: the same
 * reboard {@code launchWorld} performs, fired automatically instead of from a
 * click.</p>
 *
 * <p>Only singleplayer is intercepted. On a remote server the client cannot
 * create a world, so immediate respawn keeps its vanilla meaning there — the same
 * split {@code NarrativeDeathScreen.remote()} already makes for the reboard
 * button.</p>
 *
 * <p>Called from {@code ClientPacketListenerInstantRespawnMixin} at the head of
 * the combat-kill handler, which runs twice per death: once on the netty thread
 * (before vanilla's re-dispatch) and once on the client thread. Only the client-
 * thread pass acts; see {@link #shouldStartNewWorld}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class InstantRespawnReboard {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Set once a reboard has been scheduled and never cleared for the life of the
     * client's connection to that world — the world is being torn down, so a second
     * death packet (or a duplicate combat-kill for the same death) must not queue a
     * second {@code launchWorld}. Cleared when the next world's client player logs in
     * (see {@link #onLoggingIn}).
     */
    private static volatile boolean launchScheduled = false;

    private InstantRespawnReboard() {}

    /** Re-arm for the world that just loaded, so each world gets exactly one automatic reboard. */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        launchScheduled = false;
    }

    /**
     * Handle a combat-kill packet for {@code deadEntityId}. Returns {@code true}
     * when Dungeon Train has taken the death over — the caller must then cancel
     * vanilla's handling (no death screen, no respawn packet).
     */
    public static boolean interceptDeath(int deadEntityId) {
        Minecraft mc = Minecraft.getInstance();
        boolean clientThread = mc.isSameThread();
        // The level lookup below is only safe on the client thread, so the netty
        // pass is answered before it — the same answer shouldStartNewWorld gives.
        if (!clientThread) return false;

        LocalPlayer player = mc.player;
        boolean localPlayerDied = player != null
                && mc.level != null
                && mc.level.getEntity(deadEntityId) == player;
        boolean act = shouldStartNewWorld(
                clientThread,
                localPlayerDied,
                player != null && player.shouldShowDeathScreen(),
                mc.getSingleplayerServer() != null,
                launchScheduled);
        if (!act) return false;

        launchScheduled = true;
        LOGGER.info("InstantRespawnReboard: doImmediateRespawn is on — reboarding into a fresh world");
        // Deferred to the next client-loop task drain rather than run inline: the
        // reboard tears down the integrated server and the connection this packet
        // is still being dispatched on.
        mc.execute(() -> DeathScreenLayoutHandler.launchWorld(
                mc.screen != null ? mc.screen : new TitleScreen(),
                false,
                // Same Shift contract as the death screen's reboard chip: held
                // preserves the current game mode, otherwise the next run is survival.
                !Screen.hasShiftDown(),
                // Never ask here. Vanilla's death screen is suppressed on this path, so there is
                // no menu to return to if the player dismisses a prompt, and launchScheduled above
                // is already set — a dismissal would strand them dead with no way to reboard. The
                // last answer is reused instead.
                false));
        return true;
    }

    /**
     * Pure decision behind {@link #interceptDeath}, split out so it can be tested
     * without a client.
     *
     * @param clientThread    the packet handler is on the client thread. The netty-thread
     *                        pass must decline: vanilla re-dispatches the same packet to
     *                        the client thread, and acting on the first pass would both
     *                        touch the level off-thread and double-fire.
     * @param localPlayerDied the dead entity is this client's player, not another player
     *                        visible on a LAN world.
     * @param showDeathScreen {@code Player.shouldShowDeathScreen()} — the client's mirror
     *                        of {@code doImmediateRespawn} (false when the rule is on).
     *                        With a death screen coming, the narrative screen handles the
     *                        run end as usual.
     * @param singleplayer    an integrated server is running, so a next world can be created.
     * @param launchScheduled a reboard is already queued for this death.
     */
    static boolean shouldStartNewWorld(
            boolean clientThread,
            boolean localPlayerDied,
            boolean showDeathScreen,
            boolean singleplayer,
            boolean launchScheduled
    ) {
        return clientThread
                && localPlayerDied
                && !showDeathScreen
                && singleplayer
                && !launchScheduled;
    }
}
