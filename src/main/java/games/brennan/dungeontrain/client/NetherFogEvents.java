package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Client game-bus hooks for the Nether transition band's atmosphere — the counterpart
 * to {@link VoidSkyEvents} for the End band:
 * <ul>
 *   <li>{@link ViewportEvent.ComputeFogColor} — blends fog toward the Nether's red/orange
 *       (nether_wastes) as the nether intensity {@code n} rises, so the world glows red
 *       through the netherrack core.</li>
 *   <li>{@link ClientPlayerNetworkEvent.LoggingOut} — clears the synced band state.</li>
 * </ul>
 * Cloud suppression piggybacks on the shared {@code LevelRendererVoidSkyMixin}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class NetherFogEvents {

    /** Target fog colour at full intensity — vanilla nether_wastes fog (0x330808). */
    private static final float NETHER_FOG_R = 0.2f;
    private static final float NETHER_FOG_G = 0.03f;
    private static final float NETHER_FOG_B = 0.03f;

    private NetherFogEvents() {}

    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        double n = ClientNetherBand.netherIntensityAt(event.getCamera().getPosition().x);
        if (n <= 0.0) return;
        float t = (float) Math.min(1.0, n);
        event.setRed(lerp(event.getRed(), NETHER_FOG_R, t));
        event.setGreen(lerp(event.getGreen(), NETHER_FOG_G, t));
        event.setBlue(lerp(event.getBlue(), NETHER_FOG_B, t));
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientNetherBand.reset();
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }
}
