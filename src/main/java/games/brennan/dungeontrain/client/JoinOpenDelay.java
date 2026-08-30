package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import javax.annotation.Nullable;

/**
 * Holds the world open back for the length of the menu dissolve, so the dissolve has frames to
 * play on.
 *
 * <p>It has to, because of where the client's frames actually are. {@code Minecraft.doWorldLoad}
 * blocks the render thread for about a second reading level data and reloading resource packs, and
 * measurement showed <b>exactly one frame</b> rendering between the button press and that block,
 * then none at all until the world-load screen. A fade timed across that window is invisible no
 * matter how it is tuned — the last frame before the block simply sits frozen on screen. Frames
 * only flow <em>before</em> the open begins, so that is where the menu has to dissolve.</p>
 *
 * <p>So {@code WorldOpenFlowsDelayMixin} parks the real open here and returns; the normal game loop
 * keeps running and keeps painting {@link WorldOpenLoadingScreen} over the menu it is fading out;
 * and once {@link JoinIntroFade#menuAlpha()} reaches zero the open runs for real. Deferring rather
 * than sleeping is the point — a sleep would block the very frames it is waiting for.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class JoinOpenDelay {

    /**
     * Ceiling on the hold, in client ticks, in case the fade clock is somehow never armed. The
     * whole hand-off is ~350 ms (7 ticks); this is a backstop against a world that never opens,
     * which would be a far worse bug than a fade that gets cut short.
     */
    private static final int MAX_HELD_TICKS = 20;

    @Nullable private static Runnable pending;
    private static int heldTicks;
    /** Held across {@code open.run()} — see {@link #isReleasing()}. */
    private static boolean releasing;

    private JoinOpenDelay() {}

    /**
     * True while the parked open is being run. This, and not "is something parked", is the mixin's
     * re-entry guard: the parked call re-enters the very method it was captured from, and by then
     * it has already been unparked — so testing for a parked open would park it again, forever.
     */
    public static boolean isReleasing() {
        return releasing;
    }

    /** Park the world open until the menu has finished dissolving. */
    public static void defer(Runnable open) {
        pending = open;
        heldTicks = 0;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Runnable open = pending;
        if (open == null) return;
        // Held for the WHOLE hand-off, not just the menu dissolve: the open blocks the render
        // thread, so whatever is on screen when it starts is what stays frozen there for the next
        // second. That should be the finished themed panel, never a half-dissolved menu.
        if (JoinIntroFade.isFading() && ++heldTicks < MAX_HELD_TICKS) {
            return;
        }
        pending = null;
        heldTicks = 0;
        releasing = true;
        try {
            open.run();
        } finally {
            // In a finally: the open can throw (a corrupt save, a datapack error), and a stuck
            // flag would silently disable the hand-off for the rest of the session.
            releasing = false;
        }
    }

    /** Dropped with the rest of the timeline, so a cancelled join can't strand a parked open. */
    public static void reset() {
        pending = null;
        heldTicks = 0;
        // `releasing` is deliberately not cleared here: reset() runs from inside the open it is
        // guarding (beginJoin → reset), and clearing it there would re-open the loop it prevents.
    }
}
