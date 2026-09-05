package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import games.brennan.dungeontrain.client.ClientNetherBand;
import games.brennan.dungeontrain.client.ClientVoidBand;
import games.brennan.dungeontrain.client.NetherSkyRenderer;
import games.brennan.dungeontrain.client.UpsideDownSkyRenderer;
import games.brennan.dungeontrain.client.VoidSkyRenderer;
import games.brennan.dungeontrain.client.skybox.SkyboxStencil;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Train-band atmosphere hooks on {@code LevelRenderer}:
 * <ul>
 *   <li>{@code renderSky} TAIL → overlay the band skybox at its opacity, after vanilla has
 *       drawn the overworld sky (clean crossfade, no pop): the End starfield
 *       ({@link VoidSkyRenderer}) and the Nether fog-colour fill ({@link NetherSkyRenderer}).
 *       The two bands never overlap in world-X, so at most one paints.</li>
 *   <li>{@code renderClouds} HEAD → cancel cloud rendering once the End or Nether sky has
 *       mostly faded in, so clouds disappear over the void/End rather than floating
 *       incongruously above it.</li>
 *   <li>{@code renderClouds} getCloudHeight → records the cloud plane vanilla's pass read. The
 *       upside-down band's sinking of that plane lives on {@code getCloudHeight()} itself, in
 *       {@code DimensionSpecialEffectsCloudHeightMixin}, so it also reaches Iris' uniform.</li>
 *   <li>{@code renderSky} HEAD → under a shader pack being told this frame is the Nether or the End,
 *       draw what vanilla draws there (the End box, or nothing) and cancel the overworld sky.</li>
 *   <li>{@code renderSnowAndRain} HEAD → cancel falling rain/snow over the Nether core and the
 *       End band, so storms don't rain on the hellscape or into the void (neither the Nether nor
 *       the End has weather).</li>
 *   <li>{@code tickRain} HEAD → in the End band, also cancel the rain splash particles and rain
 *       ambience that keep playing even with the sheets hidden.</li>
 * </ul>
 *
 * <p>Two of these hooks also guard the <b>re-entrant</b> {@code renderSky} call that
 * {@link SkyboxStencil} makes to paint the above-ground sky inside a
 * {@code skybox_block}'s hole — see {@link SkyboxStencil#isDrawingSurfaceSky()}. During that
 * second call the band overlays must not paint again (they already did, in the real sky pass),
 * and vanilla's black void plane must be skipped, since showing the sky as it looks from above
 * ground is the entire point of that variant.</p>
 */
// Priority 1500: Iris injects at renderSky HEAD too (its render-phase bookkeeping), at the default
// 1000. Ours runs after it, so a spoofed frame's sky is drawn — or cancelled — in exactly the phase
// vanilla's own End/Nether branches would run in.
@Mixin(value = LevelRenderer.class, priority = 1500)
public abstract class LevelRendererVoidSkyMixin {

    /** Above this End-sky intensity, clouds are hidden. */
    private static final double DUNGEONTRAIN_CLOUD_HIDE_THRESHOLD = 0.5;

    /**
     * Under a shader pack that is being told this frame is the Nether or the End, replace the
     * overworld sky pass with what vanilla draws in that dimension: the End's sky box, or nothing
     * for the Nether. The pack's composite then paints its own atmosphere where the depth is still
     * the far plane, exactly as it does in the real dimension. The TAIL overlays below never run on
     * these frames; they belong to the vanilla path and to the pack's overworld frames of a fade.
     */
    @Inject(
            method = "renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void dungeontrain$spoofedWorldSky(Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
                                              Camera camera, boolean isFoggy, Runnable skyFogSetup, CallbackInfo ci) {
        games.brennan.dungeontrain.client.shader.ShaderWorld.World world =
            games.brennan.dungeontrain.client.shader.ShaderWorld.reporting();
        if (world == null || SkyboxStencil.isDrawingSurfaceSky()) return;
        if (world == games.brennan.dungeontrain.client.shader.ShaderWorld.World.END) {
            // Vanilla's renderEndSky: the end_sky box through position_tex_color, no depth.
            VoidSkyRenderer.renderAsSkySource(frustumMatrix);
            ci.cancel();
        } else if (world == games.brennan.dungeontrain.client.shader.ShaderWorld.World.NETHER) {
            // Vanilla draws no sky in the Nether; the fog colour (already tinted by NetherFogEvents)
            // is what the pack's Nether programs start from.
            ci.cancel();
        }
    }

    @Inject(
            method = "renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
            at = @At("TAIL")
    )
    private void dungeontrain$bandSkyOverlay(Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
                                             Camera camera, boolean isFoggy, Runnable skyFogSetup, CallbackInfo ci) {
        // Re-entrant call from the skybox stencil pass: the band overlays already painted
        // during the real sky pass, and repainting them would stack their alpha.
        if (SkyboxStencil.isDrawingSurfaceSky()) return;
        VoidSkyRenderer.renderOverlay(frustumMatrix, camera, isFoggy);
        NetherSkyRenderer.renderOverlay(frustumMatrix, camera, isFoggy);
        UpsideDownSkyRenderer.renderOverlay(frustumMatrix, camera, partialTick, isFoggy);
    }

    /**
     * Suppress vanilla's black void plane while the skybox stencil pass is drawing the
     * above-ground sky.
     *
     * <p>This is the single line that makes {@code skybox_block} work underground. Vanilla's
     * {@code renderSky} is entirely Y-independent <em>except</em> for its final block, which
     * draws {@code darkBuffer} — an opaque black dome over the lower hemisphere — whenever the
     * player's eye is below the level's horizon height. Left in, a skybox block below y=63
     * shows sky and stars looking up but solid black looking level or down.</p>
     *
     * <p>Implemented by driving the horizon height the comparison subtracts to negative
     * infinity, so {@code eyeY - horizon} is positive and the branch is not taken. That is the
     * smallest possible intervention: the ordinary sky pass is untouched, so the rest of the
     * world still gets its normal void plane.</p>
     */
    @ModifyExpressionValue(
            method = "renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
            at = @At(
                    value = "INVOKE",
                    // Owner is the CONCRETE type, not LevelData: ClientLevel#getLevelData()
                    // is declared to return ClientLevelData, so javac emits an invokevirtual
                    // against that class and Mixin matches on the emitted owner.
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel$ClientLevelData;getHorizonHeight(Lnet/minecraft/world/level/LevelHeightAccessor;)D"
            )
    )
    private double dungeontrain$suppressVoidPlaneForSkybox(double original) {
        // Horizon height is only read to compute `eyeY - horizon < 0`. Returning negative
        // infinity makes that difference positive, so the dark dome is skipped.
        return SkyboxStencil.isDrawingSurfaceSky() ? Double.NEGATIVE_INFINITY : original;
    }

    @Inject(
            method = "renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FDDD)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void dungeontrain$hideCloudsInVoid(com.mojang.blaze3d.vertex.PoseStack poseStack, Matrix4f frustumMatrix,
                                               Matrix4f projectionMatrix, float partialTick,
                                               double camX, double camY, double camZ, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        boolean hide = ClientVoidBand.endSkyIntensityAt(camX) > DUNGEONTRAIN_CLOUD_HIDE_THRESHOLD
                || ClientNetherBand.netherIntensityAt(camX) > DUNGEONTRAIN_CLOUD_HIDE_THRESHOLD;
        // Recorded whether or not it hides: that this hook ran AT ALL is the thing worth knowing.
        // Both of DT's cloud behaviours go through vanilla's cloud pass, so a pack that draws its
        // own clouds in composite never calls it and silently keeps both of them off.
        if (games.brennan.dungeontrain.client.ShaderDiagnostics.recording()) {
            games.brennan.dungeontrain.client.ShaderDiagnostics.recordCloudsHook(hide);
        }
        if (hide) {
            ci.cancel();
        }
    }

    /**
     * The cloud plane vanilla's cloud pass is about to draw at. The lowering itself now happens in
     * {@code DimensionSpecialEffectsCloudHeightMixin} — on {@code getCloudHeight()} itself, so it
     * also reaches Iris' {@code cloudHeight} uniform — and this only records that vanilla's pass
     * read it. Recorded on EVERY call, including the ones that change nothing: recording only the
     * lowering made "this hook never ran" and "it ran where the band is zero" the same reading, and
     * those are entirely different faults.
     */
    @ModifyExpressionValue(
            method = "renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FDDD)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;getCloudHeight()F"
            )
    )
    private float dungeontrain$recordCloudPlane(float applied) {
        if (games.brennan.dungeontrain.client.ShaderDiagnostics.recording()) {
            games.brennan.dungeontrain.client.ShaderDiagnostics.recordCloudHeight(
                games.brennan.dungeontrain.client.ShaderDiagnostics.cloudHeightVanilla(), applied);
        }
        return applied;
    }

    /**
     * Cancel the falling rain/snow sheets over a Nether core or an End band — neither the Nether
     * nor the End has weather, so a storm must not visibly rain on the hellscape or into the void.
     * Shares the cloud-hide threshold on the same ramps, so the weather stops exactly where the
     * clouds do rather than at a second, separate edge.
     */
    @Inject(
            method = "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void dungeontrain$hideWeatherInBands(net.minecraft.client.renderer.LightTexture lightTexture,
                                                 float partialTick, double camX, double camY, double camZ,
                                                 CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        if (ClientNetherBand.netherIntensityAt(camX) > DUNGEONTRAIN_CLOUD_HIDE_THRESHOLD
                || ClientVoidBand.endSkyIntensityAt(camX) > DUNGEONTRAIN_CLOUD_HIDE_THRESHOLD) {
            ci.cancel();
        }
    }

    /**
     * Silence the rest of the weather in the End band. {@code renderSnowAndRain} only draws the
     * falling sheets; {@code tickRain} is what spawns the ground splash particles and plays the
     * rain ambience, so both are needed before the band actually reads as weatherless. Gated on
     * the same End-sky ramp and threshold as the sheets, evaluated at the camera's own X.
     *
     * <p>End band only — the Nether band keeps its existing behaviour (its sheets are hidden, its
     * ambience is not), which is left alone here rather than changed as a side effect.</p>
     */
    @Inject(method = "tickRain(Lnet/minecraft/client/Camera;)V", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$hideRainAmbienceInEndBand(Camera camera, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        if (ClientVoidBand.endSkyIntensityAt(camera.getPosition().x) > DUNGEONTRAIN_CLOUD_HIDE_THRESHOLD) {
            ci.cancel();
        }
    }
}
