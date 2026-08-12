package games.brennan.dungeontrain.client.snapshot;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

/**
 * Keeps players out of the Train Builder's template photos.
 *
 * <p>{@link RideSnapshotCapture} takes those photos by rendering the world an extra time from a
 * fixed pose, with the camera type forced to third person so the pass reads as a detached view —
 * which also means the local player renders. That pose is where the opening cinematic ended, a few
 * blocks off the carriage it just showed you, and the person who asked for the photo is standing
 * inside that same volume: their head, and often their whole body and nameplate, ends up in front
 * of the template the picture exists to illustrate.</p>
 *
 * <p>Cancelling {@code RenderPlayerEvent.Pre} skips the entire {@code PlayerRenderer.render} call,
 * so the model, held items, worn armour and the nameplate all go with it. It is cancelled for
 * <em>every</em> player, not just the local one — a second player in a builder world is no more
 * welcome in the shot than the first.</p>
 *
 * <p>The flag is only ever set inside the nested capture pass (and cleared in its {@code finally}),
 * so the on-screen view is untouched. Doing this with {@code Entity#setInvisible} instead would mean
 * mutating replicated entity state mid-frame, which flickers on screen and tangles with invisibility
 * potions; a render event is client-only and reverts by itself.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class SnapshotPlayerHider {

    private SnapshotPlayerHider() {}

    @SubscribeEvent
    static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        if (RideSnapshotCapture.isHidingPlayers()) {
            event.setCanceled(true);
        }
    }
}
