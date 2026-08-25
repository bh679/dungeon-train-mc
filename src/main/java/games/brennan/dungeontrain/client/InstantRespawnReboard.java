package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
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
 * <p><b>Abandoning a run is the exception.</b> The pause menu's "Abandon This Run"
 * ends the run by killing the player ({@code AbandonRunPacket}), but the player
 * asked for the run to end — they should get the recap and choose what happens
 * next, not be dropped into a fresh world mid-click. {@link #expectAbandonedRun()}
 * arms that case, and the resulting death opens the death screen exactly as the
 * rule-off path would.</p>
 *
 * <p>Only singleplayer is intercepted. On a remote server the client cannot
 * create a world, so immediate respawn keeps its vanilla meaning there — the same
 * split {@code NarrativeDeathScreen.remote()} already makes for the reboard
 * button. (The Abandon button itself is singleplayer-only, see
 * {@code PauseMenuLayoutHandler}.)</p>
 *
 * <p>Called from {@code ClientPacketListenerInstantRespawnMixin} at the head of
 * the combat-kill handler, which runs twice per death: once on the netty thread
 * (before vanilla's re-dispatch) and once on the client thread. Only the client-
 * thread pass acts; see {@link #decide}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class InstantRespawnReboard {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * How long an armed "abandon" stays valid. The kill is server-side and lands
     * within a tick or two of the click, so this is generous; its job is to make
     * sure an abandon the server declined (the player was already dying, or the
     * packet never landed) cannot silently disarm immediate respawn for the rest
     * of the world.
     */
    private static final long ABANDON_WINDOW_MILLIS = 10_000L;

    /**
     * Set once a reboard has been scheduled and never cleared for the life of the
     * client's connection to that world — the world is being torn down, so a second
     * death packet (or a duplicate combat-kill for the same death) must not queue a
     * second {@code launchWorld}. Cleared when the next world's client player logs in
     * (see {@link #onLoggingIn}).
     */
    private static volatile boolean launchScheduled = false;

    /**
     * Wall-clock stamp of the last "Abandon This Run" click, or {@code 0} when no
     * abandon is armed. Consumed by the death it belongs to, cleared on login, and
     * expired by {@link #ABANDON_WINDOW_MILLIS}.
     */
    private static volatile long abandonRequestedAtMillis = 0L;

    /** What {@link #interceptDeath} does with a combat-kill packet for the local player. */
    enum Outcome {
        /** Leave the packet to vanilla — it either opens the death screen or respawns in place. */
        VANILLA,
        /** Immediate respawn on a DT run: reboard into a fresh world automatically. */
        NEW_WORLD,
        /** Immediate respawn, but the player abandoned the run: open the death screen instead. */
        DEATH_SCREEN
    }

    private InstantRespawnReboard() {}

    /** Re-arm for the world that just loaded, so each world gets exactly one automatic reboard. */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        launchScheduled = false;
        abandonRequestedAtMillis = 0L;
    }

    /**
     * Called from the pause menu just before {@code AbandonRunPacket} goes out: the
     * next death is a deliberate end-of-run, so it must show the death screen even
     * when immediate respawn would otherwise reboard automatically.
     */
    public static void expectAbandonedRun() {
        abandonRequestedAtMillis = System.currentTimeMillis();
    }

    /**
     * Handle a combat-kill packet for {@code deadEntityId}. Returns {@code true}
     * when Dungeon Train has taken the death over — the caller must then cancel
     * vanilla's handling (no vanilla death screen, no respawn packet).
     *
     * @param deathMessage the packet's death message, used verbatim when this opens
     *                     the death screen so the title reads the same as vanilla's
     *                     own rule-off path.
     */
    public static boolean interceptDeath(int deadEntityId, Component deathMessage) {
        Minecraft mc = Minecraft.getInstance();
        boolean clientThread = mc.isSameThread();
        // The level lookup below is only safe on the client thread, so the netty
        // pass is answered before it — the same answer decide() gives.
        if (!clientThread) return false;

        LocalPlayer player = mc.player;
        boolean localPlayerDied = player != null
                && mc.level != null
                && mc.level.getEntity(deadEntityId) == player;
        Outcome outcome = decide(
                clientThread,
                localPlayerDied,
                player != null && player.shouldShowDeathScreen(),
                mc.getSingleplayerServer() != null,
                launchScheduled,
                abandonArmed());

        switch (outcome) {
            case DEATH_SCREEN -> {
                abandonRequestedAtMillis = 0L;
                LOGGER.info("InstantRespawnReboard: run abandoned — showing the death screen instead of reboarding");
                boolean hardcore = mc.level != null && mc.level.getLevelData().isHardcore();
                // Deferred like the reboard below: this runs inside the packet
                // handler, and DeathScreenLayoutHandler swaps the screen as it opens.
                mc.execute(() -> mc.setScreen(new DeathScreen(deathMessage, hardcore)));
                return true;
            }
            case NEW_WORLD -> {
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
            case VANILLA -> {
                return false;
            }
        }
        return false;
    }

    /** True when an "Abandon This Run" click is armed and still inside its window. */
    private static boolean abandonArmed() {
        long stamp = abandonRequestedAtMillis;
        return stamp != 0L && System.currentTimeMillis() - stamp <= ABANDON_WINDOW_MILLIS;
    }

    /**
     * Pure decision behind {@link #interceptDeath}, split out so it can be tested
     * without a client.
     *
     * @param clientThread     the packet handler is on the client thread. The netty-thread
     *                         pass must decline: vanilla re-dispatches the same packet to
     *                         the client thread, and acting on the first pass would both
     *                         touch the level off-thread and double-fire.
     * @param localPlayerDied  the dead entity is this client's player, not another player
     *                         visible on a LAN world.
     * @param showDeathScreen  {@code Player.shouldShowDeathScreen()} — the client's mirror
     *                         of {@code doImmediateRespawn} (false when the rule is on).
     *                         With a death screen coming, vanilla opens it and the narrative
     *                         screen handles the run end as usual.
     * @param singleplayer     an integrated server is running, so a next world can be created.
     * @param launchScheduled  a reboard is already queued for this death.
     * @param abandonRequested this death is the pause menu's "Abandon This Run", so the player
     *                         gets the recap rather than an automatic reboard.
     */
    static Outcome decide(
            boolean clientThread,
            boolean localPlayerDied,
            boolean showDeathScreen,
            boolean singleplayer,
            boolean launchScheduled,
            boolean abandonRequested
    ) {
        if (!clientThread || !localPlayerDied || showDeathScreen) return Outcome.VANILLA;
        if (abandonRequested) return Outcome.DEATH_SCREEN;
        if (singleplayer && !launchScheduled) return Outcome.NEW_WORLD;
        return Outcome.VANILLA;
    }
}
