package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * Client-side "deck hold": keeps the local player from sinking below the train
 * deck for a short window after an on-train spawn.
 *
 * <p><b>Why this is client-side.</b> At spawn the server thread can stall for
 * seconds (eager-fill appender), but the client keeps ticking in real time and
 * free-falls the local player off the just-teleported deck onto the world track
 * bed under the train. The local player's position is client-authoritative, so a
 * server-side teleport correction can't keep up with the real-time fall. Instead,
 * for {@link SpawnDeckHoldPacket}'s window, this clamps the local player's Y to
 * the deck surface every client tick. The clamp floor is the deck top, so once
 * Sable's collision-carry engages the player rests exactly on it.</p>
 *
 * <p>Only the vertical axis is constrained — the player may still walk and the
 * train slides forward beneath them (a small backward drift onto the carriages
 * behind the front pad, which are continuous, so they stay on the train).</p>
 *
 * <p>After the hold ends, a short {@link #VERIFY_TICKS} post-check logs whether
 * the player actually stayed on the deck (carry/server held them) or dropped —
 * the one fact the in-hold logs can't reveal.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class SpawnDeckHold {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Consecutive unaided on-deck ticks that prove Sable's carry has taken over. */
    private static final int RELEASE_CLEAN_TICKS = 5;

    /** Ticks after the hold ends before the post-check samples the player's Y. */
    private static final int VERIFY_TICKS = 40;

    /** A drop of more than this below the deck top counts as "fell under the train". */
    private static final double DROP_TOLERANCE = 1.0;

    private static volatile boolean active = false;
    private static double deckTopY;
    private static int remaining;
    private static int cleanStreak;
    private static int clampsFired;
    private static int verifyRemaining;

    private SpawnDeckHold() {}

    /**
     * Begin (or restart) the hold. {@code durationTicks} is the hard cap — the
     * hold normally releases earlier, as soon as carry engages. Called from
     * {@link SpawnDeckHoldPacket#handle}.
     */
    public static void begin(double deckTopY, int durationTicks) {
        SpawnDeckHold.deckTopY = deckTopY;
        SpawnDeckHold.remaining = Math.max(1, durationTicks);
        SpawnDeckHold.cleanStreak = 0;
        SpawnDeckHold.clampsFired = 0;
        SpawnDeckHold.verifyRemaining = 0;
        active = true;
        LOGGER.debug("[DungeonTrain] Spawn deck-hold begin: deckTopY={} cap={}t",
            String.format("%.2f", deckTopY), SpawnDeckHold.remaining);
    }

    /** End the hold and arm the post-release verification sample. */
    private static void endHold() {
        active = false;
        verifyRemaining = VERIFY_TICKS;
    }

    /**
     * Clamp at {@code Post} — after the player's own movement for the tick has
     * run, so we undo any fall it produced rather than racing it. After the hold
     * ends, run the post-release verification sample.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            active = false;
            verifyRemaining = 0;
            return;
        }

        if (active) {
            if (player.getY() < deckTopY) {
                // Snap back up onto the deck and kill any downward velocity so the
                // player rides instead of accelerating through the hollow slab.
                player.setPos(player.getX(), deckTopY, player.getZ());
                // Match the previous-tick Y so the renderer doesn't interpolate a dip.
                player.yo = deckTopY;
                player.setOnGround(true);
                Vec3 dm = player.getDeltaMovement();
                if (dm.y < 0.0) {
                    player.setDeltaMovement(dm.x, 0.0, dm.z);
                }
                clampsFired++;
                cleanStreak = 0;
            } else if (++cleanStreak >= RELEASE_CLEAN_TICKS) {
                // Player stayed on the deck unaided — Sable's carry has taken over.
                LOGGER.debug("[DungeonTrain] Spawn deck-hold released: carry engaged after {} corrections ({}t cap left)",
                    clampsFired, remaining);
                endHold();
                return;
            }

            if (--remaining <= 0) {
                // Hold bridged the spawn-storm window; the player is on the deck
                // and stays (server keeps them) — carry simply didn't re-engage
                // early, so the full window ran. Benign.
                LOGGER.debug("[DungeonTrain] Spawn deck-hold cap reached after {} corrections (finalY={}, deckTop={})",
                    clampsFired, String.format("%.2f", player.getY()), String.format("%.2f", deckTopY));
                endHold();
            }
            return;
        }

        // Post-release safety check — should never fire, but if a spawn ever
        // drops under the train after the hold, surface it loudly for support.
        if (verifyRemaining > 0 && --verifyRemaining == 0
            && player.getY() < deckTopY - DROP_TOLERANCE) {
            LOGGER.warn("[DungeonTrain] Spawn deck-hold post-check: player dropped to y={} ({} below deck top {}) — carry hand-off failed",
                String.format("%.2f", player.getY()),
                String.format("%.2f", deckTopY - player.getY()),
                String.format("%.2f", deckTopY));
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        active = false;
        verifyRemaining = 0;
    }
}
