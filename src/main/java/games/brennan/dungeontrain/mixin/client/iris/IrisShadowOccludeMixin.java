package games.brennan.dungeontrain.mixin.client.iris;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.shader.ShaderBisect;
import games.brennan.dungeontrain.client.shader.ShaderWorld;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Puts everything in shadow while a band is being rendered as the Nether or the End.
 *
 * <h2>Why</h2>
 * <p>Confirmed the hard way: with the pack's own shadow map switched off, a carriage under a Nether
 * roof is fully shaded exactly as it should be; with it on, the train is lit with patchy shadows
 * while the terrain beside it is not. The pack's Nether programs still sample a shadow map, and that
 * map was rendered for a light the real Nether does not have. Four attempts to move or dim that
 * light changed nothing, because a shadow map is not a light — it is a depth image, and it said the
 * train was in the open.</p>
 *
 * <p>The real Nether and End have no shadow-casting light at all, so the truthful imitation is a
 * shadow map that answers "occluded" for every sample. After Iris has finished rendering the pass,
 * both shadow depth textures — with and without translucents, since packs read either — are cleared
 * to depth {@code 0.0}: the nearest possible value, closer than any geometry, so every comparison
 * concludes the sample is behind something. Everything is in shadow, the pack's own lighting maths
 * does the rest, and a carriage under a roof looks like a carriage under a roof.</p>
 *
 * <p>Done at RETURN, after Iris' own clears and geometry, so the map is not re-cleared to "open"
 * behind our back, and after Iris has rebound the main target, which is rebound again on the way
 * out. Scoped to the reported world, so the overworld's shadows are untouched — that is where the
 * shadow map was doing its job correctly all along.</p>
 *
 * <p>Iris stays off the compile classpath: the shadow targets are reached reflectively, once.</p>
 */
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public abstract class IrisShadowOccludeMixin {

    private static final Logger DUNGEONTRAIN_LOGGER = LogUtils.getLogger();

    private static boolean dungeontrain$announced = false;
    private static boolean dungeontrain$resolved = false;
    private static Field dungeontrain$targetsField;
    private static Method dungeontrain$depthTexture;
    private static Method dungeontrain$depthTextureNoTranslucents;
    private static Method dungeontrain$textureId;
    /** A throwaway framebuffer of our own, so nothing of Iris' has to be rebound to clear a texture. */
    private static int dungeontrain$clearFbo = 0;

    @Inject(method = "renderShadows", at = @At("RETURN"))
    private void dungeontrain$occludeEverything(CallbackInfo ci) {
        if (!dungeontrain$announced) {
            dungeontrain$announced = true;
            DUNGEONTRAIN_LOGGER.info("[DungeonTrain] Iris shadow-occlude hook is live.");
        }
        ShaderWorld.World w = ShaderWorld.reporting();
        if (w == null || w == ShaderWorld.World.OVERWORLD) return;
        if (!ShaderBisect.spoofEnabled()) return;

        dungeontrain$resolve();
        if (dungeontrain$targetsField == null) return;
        try {
            Object targets = dungeontrain$targetsField.get(this);
            if (targets == null) return;
            int tex0 = dungeontrain$idOf(dungeontrain$depthTexture.invoke(targets));
            int tex1 = dungeontrain$idOf(dungeontrain$depthTextureNoTranslucents.invoke(targets));
            dungeontrain$clearToOccluded(tex0);
            if (tex1 != tex0) dungeontrain$clearToOccluded(tex1);
        } catch (Throwable t) {
            DUNGEONTRAIN_LOGGER.warn("[DungeonTrain] Shadow occlude skipped: {}", t.toString());
        } finally {
            // Iris rebound the main target before returning; the clears above rebound ours. Put it back.
            RenderSystem.clearDepth(1.0);
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        }
    }

    private static int dungeontrain$idOf(Object depthTexture) throws Exception {
        return depthTexture == null ? 0 : (Integer) dungeontrain$textureId.invoke(depthTexture);
    }

    /** Clear one depth texture to 0.0 through a framebuffer of our own. */
    private static void dungeontrain$clearToOccluded(int textureId) {
        if (textureId == 0) return;
        if (dungeontrain$clearFbo == 0) dungeontrain$clearFbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, dungeontrain$clearFbo);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
            GL11.GL_TEXTURE_2D, textureId, 0);
        // glClear honours the depth write mask, so it must be on for the clear to land.
        RenderSystem.depthMask(true);
        RenderSystem.clearDepth(0.0);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }

    private static void dungeontrain$resolve() {
        if (dungeontrain$resolved) return;
        dungeontrain$resolved = true;
        try {
            Class<?> renderer = Class.forName("net.irisshaders.iris.shadows.ShadowRenderer");
            Field f = renderer.getDeclaredField("targets");
            f.setAccessible(true);
            Class<?> targets = Class.forName("net.irisshaders.iris.shadows.ShadowRenderTargets");
            Method d0 = targets.getMethod("getDepthTexture");
            Method d1 = targets.getMethod("getDepthTextureNoTranslucents");
            Class<?> depth = Class.forName("net.irisshaders.iris.targets.DepthTexture");
            Method id = depth.getMethod("getTextureId");
            dungeontrain$targetsField = f;
            dungeontrain$depthTexture = d0;
            dungeontrain$depthTextureNoTranslucents = d1;
            dungeontrain$textureId = id;
        } catch (Throwable t) {
            DUNGEONTRAIN_LOGGER.warn("[DungeonTrain] Iris shadow targets unreachable; band shadows stay as the pack draws them: {}", t.toString());
        }
    }
}
