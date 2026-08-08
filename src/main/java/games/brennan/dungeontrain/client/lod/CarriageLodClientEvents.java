package games.brennan.dungeontrain.client.lod;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Client game-bus hooks driving the far-carriage render LOD:
 * <ul>
 *   <li>{@link ClientTickEvent.Post} — refresh the per-tick bake budget, sweep meshes for removed
 *       sub-levels, then reconcile every tracked carriage's tier. Runs in {@code Post} so the tier
 *       flags are settled before the frames that read them, mirroring how the server-side freeze
 *       reconcile runs after the physics tick.</li>
 *   <li>{@link LevelEvent.Unload} and {@link ClientPlayerNetworkEvent.LoggingOut} — free every
 *       baked mesh. These hold GL buffers, so a world change that left them behind would leak VRAM
 *       for the rest of the session.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CarriageLodClientEvents {

    private CarriageLodClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        CarriageMeshCache.beginTick();
        CarriageMeshCache.sweepRemoved();
        CarriageLodController.reconcile(mc);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;
        // Only the meshes need dealing with. Promoting the sub-levels first would be busywork on
        // objects that are about to be discarded anyway — and `Minecraft.level` is already
        // unreliable at this point in a world swap.
        CarriageMeshCache.clear();
        CarriageLodLog.reset();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        CarriageMeshCache.clear();
        CarriageLodLog.reset();
    }
}
