package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.event.PlayerActivityTracker.Reason;
import games.brennan.dungeontrain.net.ActivityStatePacket;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.client.Minecraft;

/**
 * Opens the pause menu when the server's AFK detection
 * ({@link games.brennan.dungeontrain.event.PlayerActivityTracker}) says the player has gone idle —
 * {@link Reason#MOUSE_IDLE} (mouse still 30 s) or {@link Reason#INPUT_IDLE} (no other input 5 min).
 * In singleplayer the integrated server stops ticking behind the pause screen, so the train does not
 * carry an absent player into danger. In multiplayer it only opens the menu. Never in Free Play
 * (read from the client-visible Free Play badge, as {@link AdvancementTabGate} does): nothing is at
 * stake there, so an interruption would only be in the way.
 *
 * <p>Driven by {@link ActivityStatePacket}, which the server already sends on every change of
 * reason — no extra traffic. <b>Edge-triggered:</b> it pauses on the transition into idle, never
 * while idle persists. Resuming sends {@code PlayerPausedPacket(false)}, which the server counts as
 * input, so the reason returns to tracking and the next idle spell is a fresh edge.</p>
 */
public final class AfkAutoPause {

    /** Reason last received; {@code -1} = none yet this session. */
    private static int lastReason = -1;

    private AfkAutoPause() {}

    /** Is this {@link Reason} ordinal one of the AFK reasons? */
    public static boolean isAfk(int reason) {
        return reason == Reason.MOUSE_IDLE.ordinal() || reason == Reason.INPUT_IDLE.ordinal();
    }

    /**
     * Pure decision: pause only on the edge into AFK, with the setting on, no screen already open
     * (death screen, inventory, editor menus, an existing pause menu), not in Free Play, and not in
     * VR, where a pause menu dropping into the headset unannounced is jarring.
     */
    public static boolean shouldPause(int prevReason, int reason, boolean enabled,
                                      boolean screenOpen, boolean freePlay, boolean vrActive) {
        if (!enabled || screenOpen || freePlay || vrActive) return false;
        return isAfk(reason) && !isAfk(prevReason);
    }

    /** Called on the client main thread from {@link ActivityStatePacket#handle}. */
    public static void onActivityState(ActivityStatePacket packet) {
        int prev = lastReason;
        lastReason = packet.reason();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !mc.player.isAlive() || mc.player.isSpectator()) {
            return;
        }
        if (shouldPause(prev, packet.reason(), ClientDisplayConfig.isAfkPauseEnabled(),
                mc.screen != null, mc.player.hasEffect(ModMobEffects.FREE_PLAY),
                VrCompat.isVivecraftPresent())) {
            mc.pauseGame(false);
        }
    }
}
