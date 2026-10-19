package games.brennan.dungeontrain.mixin.client.windy;

import games.brennan.dungeontrain.client.wind.WindyDrive;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches each Windy wind streak as it enters the particle engine and, while DT owns the wind, moves it in the
 * <b>train's frame</b>: its velocity gets the carriage's own velocity added (so on a moving train the streak
 * drifts the way the wind actually pushes you, instead of all streaming backwards past you), and it keeps its
 * speed for its whole life (no friction, no block collision — carriage blocks live in Sable's plot anyway).
 * See {@link WindyDrive#adopt}.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineWindyMixin {

    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"))
    private void dungeontrain$adoptWindStreak(Particle particle, CallbackInfo ci) {
        WindyDrive.adopt(particle);
    }
}
