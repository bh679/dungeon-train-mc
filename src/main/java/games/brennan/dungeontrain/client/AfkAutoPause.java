package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.event.PlayerActivityTracker.Reason;
import games.brennan.dungeontrain.net.ActivityStatePacket;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.client.Minecraft;

/**
 * Opens the pause menu when the server's AFK detection
 * ({@link games.brennan.dungeontrain.event.PlayerActivityTracker}) says the player has gone idle —
 * {@link Reason#MOUSE_IDLE} (mouse still 30 s) or {@link Reason#INPUT_IDLE} (no other input 5 min) —
 * or has stopped getting anywhere: {@link Reason#NO_PROGRESS}, fewer than three carriages in ten
 * minutes. Dimensional carriages are exempt from that last one without any check here: the server
 * counts time in a room (and ten minutes after leaving it) as progress, so it never reports
 * {@code NO_PROGRESS} there.
 * In singleplayer the integrated server stops ticking behind the pause screen, so the train does not
 * carry an absent player into danger. In multiplayer it only opens the menu. Never in Free Play
 * (read from the client-visible Free Play badge, as {@link AdvancementTabGate} does): nothing is at
 * stake there, so an interruption would only be in the way.
 *
 * <p>Driven by {@link ActivityStatePacket}, which the server already sends on every change of
 * reason — no extra traffic. <b>Edge-triggered:</b> it pauses on the transition into idle, never
 * while idle persists. Resuming sends {@code PlayerPausedPacket(false)}, which the server counts as
 * input, so the reason returns to tracking and the next idle spell is a fresh edge. Resuming does
 * not walk anyone through the train, though, so a {@code NO_PROGRESS} player comes back to
 * {@code NO_PROGRESS}: that pause fires only from {@link Reason#TRACKING}, and the next one waits
 * until they have crossed three carriages and stalled again.</p>
 */
public final class AfkAutoPause {

    /** Reason last received; {@code -1} = none yet this session. */
    private static int lastReason = -1;

    private AfkAutoPause() {}

    /** Is this {@link Reason} ordinal one of the away-from-the-keyboard reasons? */
    public static boolean isIdle(int reason) {
        return reason == Reason.MOUSE_IDLE.ordinal() || reason == Reason.INPUT_IDLE.ordinal();
    }

    /**
     * Pure decision: pause only on the edge into idle, or from tracking into not getting anywhere,
     * with the setting on, no screen already open (death screen, inventory, editor menus, an
     * existing pause menu), not in Free Play, and not in VR, where a pause menu dropping into the
     * headset unannounced is jarring.
     */
    public static boolean shouldPause(int prevReason, int reason, boolean enabled,
                                      boolean screenOpen, boolean freePlay, boolean vrActive) {
        if (!enabled || screenOpen || freePlay || vrActive) return false;
        if (isIdle(reason)) return !isIdle(prevReason);
        if (reason == Reason.NO_PROGRESS.ordinal()) return prevReason == Reason.TRACKING.ordinal();
        return false;
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
