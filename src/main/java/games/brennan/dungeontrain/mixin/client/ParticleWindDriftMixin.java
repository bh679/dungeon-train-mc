package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.wind.AmbientWind;
import games.brennan.dungeontrain.client.wind.WindDriftParticle;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Lets the train wind blow ambient particles ({@link AmbientWind}): each particle carries its wind weight and its
 * own drift, which eases toward the wind's so it picks up a gust instead of snapping to it.
 *
 * <p>The drift moves the particle's position after its own tick rather than its velocity, so it works for the
 * particles that set their own velocity every tick (campfire smoke, snowflakes). {@code xo/zo} are left alone, so
 * the step is interpolated like any other movement.</p>
 */
@Mixin(Particle.class)
public abstract class ParticleWindDriftMixin implements WindDriftParticle {

    @Shadow @Final protected ClientLevel level;
    @Shadow protected double x;
    @Shadow protected double y;
    @Shadow protected double z;
    @Shadow protected boolean removed;

    @Shadow public abstract void setPos(double x, double y, double z);

    @Unique private float dungeontrain$windWeight = 0.0f;
    @Unique private double dungeontrain$driftX = 0.0;
    @Unique private double dungeontrain$driftZ = 0.0;
    @Unique private int dungeontrain$shelterCheckIn = 0;
    @Unique private boolean dungeontrain$sheltered = false;

    @Override
    public void dungeontrain$setWindWeight(float weight) {
        dungeontrain$windWeight = weight;
        // Stagger the shelter checks so a burst of particles doesn't all look on the same tick.
        dungeontrain$shelterCheckIn = (int) (Math.random() * AmbientWind.SHELTER_RECHECK);
    }

    @Override
    public void dungeontrain$primeDrift() {
        if (!AmbientWind.active()) return;
        dungeontrain$driftX = AmbientWind.driftX() * dungeontrain$windWeight;
        dungeontrain$driftZ = AmbientWind.driftZ() * dungeontrain$windWeight;
    }

    @Override
    public void dungeontrain$windDrift() {
        if (dungeontrain$windWeight <= 0.0f || removed) return;
        boolean calm = !AmbientWind.active();
        if (calm && dungeontrain$driftX == 0.0 && dungeontrain$driftZ == 0.0) return;
        if (!calm && --dungeontrain$shelterCheckIn <= 0) {
            dungeontrain$shelterCheckIn = AmbientWind.SHELTER_RECHECK;
            dungeontrain$sheltered = AmbientWind.sheltered(level, x, y, z);
        }
        double tx = 0.0;
        double tz = 0.0;
        if (!calm && !dungeontrain$sheltered) {
            tx = AmbientWind.driftX() * dungeontrain$windWeight;
            tz = AmbientWind.driftZ() * dungeontrain$windWeight;
        }
        dungeontrain$driftX = dungeontrain$step(dungeontrain$driftX, tx);
        dungeontrain$driftZ = dungeontrain$step(dungeontrain$driftZ, tz);
        if (dungeontrain$driftX != 0.0 || dungeontrain$driftZ != 0.0) {
            setPos(x + dungeontrain$driftX, y, z + dungeontrain$driftZ);
        }
    }

    @Unique
    private static double dungeontrain$step(double current, double target) {
        double next = current + (target - current) * AmbientWind.DRIFT_EASE;
        return target == 0.0 && Math.abs(next) < 1.0e-4 ? 0.0 : next;
    }
}
