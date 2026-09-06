package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Keeps {@link ClientPortalSeal} current, and drops it on the way out.
 *
 * <p>{@link ViewportEvent.ComputeCameraAngles} is where the cut is decided because of <i>when</i> it
 * fires: {@code GameRenderer} raises it the moment the camera is set up and before
 * {@code LevelRenderer.renderLevel} runs, so the frustum has not culled a section yet and the frame a
 * portal swap arrives on is cut from the position the player actually arrived at. A client tick would
 * be up to fifty milliseconds stale, which for a teleport is a visible flash of the world overhead;
 * anything inside {@code renderLevel} would be after {@code setupRender} has already chosen the
 * visible sections, and unreliable under Sodium, which replaces that pass.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PortalSealEvents {

    private PortalSealEvents() {}

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Vec3 camera = event.getCamera().getPosition();
        ClientPortalSeal.beginFrame(camera.x, camera.y);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // Belt to the per-frame recompute's braces: the next frame in the next world would decide the
        // cut afresh anyway, but a stale one is exactly the kind of cache that strands a client
        // looking at nothing, and the sibling portal caches are all cleared here for the same reason.
        ClientPortalSeal.reset();
    }
}
