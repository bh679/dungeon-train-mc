package games.brennan.dungeontrain.mixin.client.windy;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the motion fields of a vanilla {@link Particle} so DT can re-aim Windy's wind streaks as they are
 * added ({@link ParticleEngineWindyMixin}). Lives in the Windy-gated config: it is only used for Windy's
 * particles.
 */
@Mixin(Particle.class)
public interface WindyParticleAccessor {

    @Accessor("xd")
    double dungeontrain$getXd();

    @Accessor("xd")
    void dungeontrain$setXd(double xd);

    @Accessor("zd")
    double dungeontrain$getZd();

    @Accessor("zd")
    void dungeontrain$setZd(double zd);

    @Accessor("friction")
    void dungeontrain$setFriction(float friction);

    @Accessor("hasPhysics")
    void dungeontrain$setHasPhysics(boolean hasPhysics);
}
