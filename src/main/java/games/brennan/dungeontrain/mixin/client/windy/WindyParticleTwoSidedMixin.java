package games.brennan.dungeontrain.mixin.client.windy;

import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.client.wind.WindyDrive;
import games.brennan.dungeontrain.client.wind.WindyStreakCopies;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.TextureSheetParticle;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * How DT draws each Windy streak while it owns the wind:
 * <ul>
 *   <li><b>From both sides.</b> Windy turns its streak quad to face along the wind instead of billboarding it, and
 *       particles render with back-face culling on, so from half the directions (looking back along the train, for
 *       one) every streak was culled. The quad is drawn a second time flipped over along its length, so it shows
 *       whichever way you look and its head still points downwind.</li>
 *   <li><b>Firmer in a gust.</b> Each streak takes {@link WindyDrive#alpha} as it draws.</li>
 *   <li><b>Past the cap, as several.</b> When the wind wants more streaks than the spawn cap allows,
 *       {@link WindyDrive#adopt} gives a streak extra copies; each is drawn here a block or two off the original
 *       (placed by a per-streak seed so they don't line up). A copy is just more quads: no spawn, no path check.</li>
 * </ul>
 *
 * <p>Applied only when {@code WindyMixinPlugin} finds {@code render} and {@code livingWindRotation} with these
 * descriptors, so a future Windy that draws streaks differently just skips it.</p>
 */
@Pseudo
@Mixin(targets = "xyz.bonfiremc.windy.particle.WindParticle", remap = false)
public abstract class WindyParticleTwoSidedMixin extends TextureSheetParticle implements WindyStreakCopies {

    /** How far a copy sits from the original: across/along the wind, and up or down. */
    @Unique
    private static final double COPY_SPREAD = 2.5;
    @Unique
    private static final double COPY_RISE = 1.5;

    @Unique
    private double[] dungeontrain$offsets = new double[0];

    private WindyParticleTwoSidedMixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
    }

    @Shadow(remap = false)
    private static Quaternionf livingWindRotation() {
        throw new AssertionError();
    }

    @Override
    public void dungeontrain$setCopies(int copies, long seed) {
        int extra = Math.max(0, copies - 1);
        double[] offsets = new double[extra * 3];
        java.util.SplittableRandom r = new java.util.SplittableRandom(seed);
        for (int i = 0; i < extra; i++) {
            offsets[i * 3] = (r.nextDouble() * 2.0 - 1.0) * COPY_SPREAD;
            offsets[i * 3 + 1] = (r.nextDouble() * 2.0 - 1.0) * COPY_RISE;
            offsets[i * 3 + 2] = (r.nextDouble() * 2.0 - 1.0) * COPY_SPREAD;
        }
        dungeontrain$offsets = offsets;
    }

    /** Every streak takes the current opacity as it draws, so a gust firms up the ones already in the air too. */
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V",
            at = @At("HEAD"), remap = false)
    private void dungeontrain$gustAlpha(VertexConsumer buffer, Camera camera, float partialTick, CallbackInfo ci) {
        if (WindyDrive.owns()) alpha = WindyDrive.alpha();
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V",
            at = @At("RETURN"), remap = false)
    private void dungeontrain$drawMore(VertexConsumer buffer, Camera camera, float partialTick, CallbackInfo ci) {
        if (!WindyDrive.owns()) return;
        Quaternionf front = livingWindRotation();
        // Flip about the quad's own horizontal axis (local X, along the streak), not about the vertical: that turns
        // the face round while keeping the sprite's head pointing downwind (a half turn about Y mirrors it).
        Quaternionf back = new Quaternionf(front).rotateX((float) Math.PI);
        renderRotatedQuad(buffer, camera, back, partialTick);
        double[] offsets = dungeontrain$offsets;
        for (int i = 0; i + 2 < offsets.length; i += 3) {
            dungeontrain$shift(offsets[i], offsets[i + 1], offsets[i + 2]);
            renderRotatedQuad(buffer, camera, front, partialTick);
            renderRotatedQuad(buffer, camera, back, partialTick);
            dungeontrain$shift(-offsets[i], -offsets[i + 1], -offsets[i + 2]);
        }
    }

    @Unique
    private void dungeontrain$shift(double dx, double dy, double dz) {
        x += dx;
        xo += dx;
        y += dy;
        yo += dy;
        z += dz;
        zo += dz;
    }
}
