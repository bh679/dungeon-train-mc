package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.wind.WindDriftParticle;
import games.brennan.dungeontrain.client.wind.WindParticleTypes;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks the train wind into ambient particles ({@code AmbientWind}):
 * <ul>
 *   <li>{@code createParticle} — the path every {@code Level.addParticle} takes — tags the new particle with its
 *       type's wind weight ({@link WindParticleTypes}). Block-break debris is added straight to the engine and
 *       never tagged.</li>
 *   <li>{@code tickParticle} — after the particle's own tick, let the wind move it.</li>
 * </ul>
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineWindDriftMixin {

    @Inject(method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;",
            at = @At("RETURN"))
    private void dungeontrain$tagWindWeight(ParticleOptions options, double x, double y, double z,
                                            double xd, double yd, double zd, CallbackInfoReturnable<Particle> cir) {
        Particle particle = cir.getReturnValue();
        if (particle == null) return;
        float w = WindParticleTypes.weight(options.getType());
        if (w > 0.0f) ((WindDriftParticle) particle).dungeontrain$setWindWeight(w);
    }

    @Inject(method = "tickParticle(Lnet/minecraft/client/particle/Particle;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;tick()V", shift = At.Shift.AFTER))
    private void dungeontrain$windDrift(Particle particle, CallbackInfo ci) {
        ((WindDriftParticle) particle).dungeontrain$windDrift();
    }
}
