package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

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
public final class UpsideDownFogEvents {

    /** Max blend toward the sky colour at full band intensity. */
    private static final float MAX_BLEND = 0.85F;

    private static final float SKY_R = ((UpsideDownSkyRenderer.SKY_RGB >> 16) & 0xFF) / 255.0F;
    private static final float SKY_G = ((UpsideDownSkyRenderer.SKY_RGB >> 8) & 0xFF) / 255.0F;
    private static final float SKY_B = (UpsideDownSkyRenderer.SKY_RGB & 0xFF) / 255.0F;

    private UpsideDownFogEvents() {}

    public static void onComputeFogColor(net.minecraft.client.Camera camera, games.brennan.dungeontrain.platform.event.DtFogColor color) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        double t = ClientUpsideDownBand.upsideDownIntensityAt(camera.getPosition().x);
        if (t <= 0.0) return;
        float blend = (float) (MAX_BLEND * Math.min(1.0, t));
        color.setRed(color.getRed() + (SKY_R - color.getRed()) * blend);
        color.setGreen(color.getGreen() + (SKY_G - color.getGreen()) * blend);
        color.setBlue(color.getBlue() + (SKY_B - color.getBlue()) * blend);
    }

    public static void onLoggingOut() {
        ClientUpsideDownBand.reset();
    }
}
