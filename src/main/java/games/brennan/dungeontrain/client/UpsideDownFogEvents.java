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
 * Client game-bus hooks for the upside-down band's atmosphere (companion to
 * {@link UpsideDownSkyRenderer}, which draws the rotating sky from a render mixin):
 * <ul>
 *   <li>{@link ViewportEvent.ComputeFogColor} — lerps the fog toward the band's day-sky colour
 *       ({@link UpsideDownSkyRenderer#SKY_RGB}) as the band intensity {@code t} rises, so the horizon
 *       matches the sky painted over it (no dark seam).</li>
 *   <li>{@link ClientPlayerNetworkEvent.LoggingOut} — clears the synced band state so it never leaks
 *       into the next world.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class UpsideDownFogEvents {

    /** Max blend toward the sky colour at full band intensity. */
    private static final float MAX_BLEND = 0.85F;

    private static final float SKY_R = ((UpsideDownSkyRenderer.SKY_RGB >> 16) & 0xFF) / 255.0F;
    private static final float SKY_G = ((UpsideDownSkyRenderer.SKY_RGB >> 8) & 0xFF) / 255.0F;
    private static final float SKY_B = (UpsideDownSkyRenderer.SKY_RGB & 0xFF) / 255.0F;

    private UpsideDownFogEvents() {}

    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        double t = ClientUpsideDownBand.upsideDownIntensityAt(event.getCamera().getPosition().x);
        if (t <= 0.0) return;
        float blend = (float) (MAX_BLEND * Math.min(1.0, t));
        event.setRed(event.getRed() + (SKY_R - event.getRed()) * blend);
        event.setGreen(event.getGreen() + (SKY_G - event.getGreen()) * blend);
        event.setBlue(event.getBlue() + (SKY_B - event.getBlue()) * blend);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientUpsideDownBand.reset();
    }
}
