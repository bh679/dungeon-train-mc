package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;

/**
 * A sky that is <b>night on both halves of the day</b>, for the {@code skybox_night} block.
 *
 * <p>It still cycles: the stars wheel on vanilla's overhead celestial axis and the moon rises,
 * crosses and sets exactly where the real one would. What never happens is daylight — the sun is
 * simply not drawn, so there is no sunrise, no sunset and no blue hour. The consequence, and the
 * intended effect, is that the moon completes two crossings per Minecraft day: once on the real
 * night, and once more where the sun would otherwise have been.</p>
 *
 * <p>Unlike {@link UpsideDownSkyRenderer} and {@link VoidSkyRenderer} this has no band-overlay
 * entry point — no region of the world renders a night sky over the real one, so the skybox block
 * is its only caller and {@link #renderAsSkySource} its only public method.</p>
 *
 * <p>Cosmetic only: the engine's real skylight is untouched, so a room walled in night skybox
 * blocks is lit by whatever light it actually has.</p>
 */
public final class NightSkyRenderer {

    /**
     * Near-black with a trace of blue. Deliberately not pure black: it separates the sky from an
     * unlit interior, and it is what the moon and stars read as "sky" against.
     */
    public static final int SKY_RGB = 0x02040C;

    private static final float MOON_SIZE = 20.0F;

    private NightSkyRenderer() {}

    /**
     * Draw the night sky unconditionally at full opacity, as a <em>sky source</em> for a skybox
     * block — see {@link VoidSkyRenderer#renderAsSkySource}.
     */
    public static void renderAsSkySource(Matrix4f frustumMatrix, float partialTick) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        SkyDomeDraw.fillDome(frustumMatrix, SKY_RGB, 1.0F);

        // One shared celestial frame, so the moon always sits among the same stars.
        float timeOfDay = level.getTimeOfDay(partialTick);
        PoseStack pose = SkyDomeDraw.celestialPose(frustumMatrix, timeOfDay);
        StarFieldRenderer.draw(pose.last().pose(), 1.0F);

        float[] uv = SkyDomeDraw.moonUv(level.getMoonPhase());
        SkyDomeDraw.drawCelestialBody(frustumMatrix, SkyDomeDraw.MOON, timeOfDay,
                MOON_SIZE, 1.0F, uv[0], uv[1], uv[2], uv[3]);
    }
}
