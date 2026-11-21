package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The click that brings the game window back to the front is not a click on what is drawn.
 *
 * <p>On macOS the mouse-down that activates the window is delivered to it as well, so a player
 * who tabs away during the death scene and clicks back lands that click on the death screen:
 * the camera cycle hands over to the recap, and a second click near the top right of the recap
 * is its Board anew chip, which tears the world down. The death screens ask here before acting
 * on a click, and ignore any that arrives within {@link #GRACE_TICKS} of the window regaining
 * focus.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class RefocusClickGuard {

    /** Clicks this soon after the window regains focus are the focusing click, not a choice. */
    static final int GRACE_TICKS = 10;

    private static boolean wasActive = true;
    private static long ticks;
    private static long regainedAt = Long.MIN_VALUE / 2;

    private RefocusClickGuard() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        tick(Minecraft.getInstance().isWindowActive());
    }

    /** Pure state step: one client tick with the window focused or not. */
    static void tick(boolean windowActive) {
        ticks++;
        if (windowActive && !wasActive) regainedAt = ticks;
        wasActive = windowActive;
    }

    /** True when a click arriving now should be dropped as the window-activating click. */
    public static boolean swallow() {
        return ticks - regainedAt < GRACE_TICKS;
    }

    static void reset() {
        wasActive = true;
        ticks = 0;
        regainedAt = Long.MIN_VALUE / 2;
    }
}
