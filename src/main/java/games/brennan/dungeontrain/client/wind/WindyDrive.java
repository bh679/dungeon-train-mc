package games.brennan.dungeontrain.client.wind;

import games.brennan.dungeontrain.mixin.client.windy.WindyParticleAccessor;
import net.minecraft.client.particle.Particle;

/**
 * The wind DT hands Windy through its Living Wind hook ({@code WindyLivingWindCompatMixin}) and applies to each
 * Windy streak as it spawns ({@code ParticleEngineWindyMixin}). Refreshed once per client tick by
 * {@link WindClientEvents} from the wind {@code visualLeadTicks} ahead — so streaks show each gust before it
 * shoves.
 *
 * <h2>Scales</h2>
 * <ul>
 *   <li><b>Strength</b> (Windy's Living Wind scale): below 0.08 is calm, 0.75+ picks the strong streak.
 *       {@link #REFERENCE_PUSH} maps DT's push onto it: very slight wind (0.002) sits just over the floor, a
 *       late steady wind (~0.014) just under the strong streak, and gusts cross it.</li>
 *   <li><b>Speed</b>: streaks fly at {@link #SPEED_BASE} + push × {@link #SPEED_PER_PUSH} blocks/tick (2.5 b/s
 *       in a breath of wind, ~5 b/s in a late steady wind, 15+ b/s in a gust); strong streaks a bit faster.</li>
 *   <li><b>Density</b>: push / {@link #DENSITY_PUSH} × Windy's base rate, capped at {@link #MAX_DENSITY}.</li>
 * </ul>
 *
 * <h2>Train frame</h2>
 * The push is felt relative to the carriage (Sable carries riders), so streaks are moved in the carriage's
 * frame too: each one gets the carriage's velocity added and keeps its speed for its whole life. Seen from the
 * train they then drift exactly the way the wind pushes you, instead of all streaming backwards.
 */
public final class WindyDrive {

    /** DT push (blocks/tick²) that reads as Living Wind strength 1. */
    static final double REFERENCE_PUSH = 0.02;
    private static final float MAX_STRENGTH = 2.0f;
    static final double SPEED_BASE = 0.1;
    static final double SPEED_PER_PUSH = 10.0;
    private static final double STRONG_SPEED = 1.3;
    /** DT push that gives Windy's base spawn rate. */
    static final double DENSITY_PUSH = 0.002;
    static final double MAX_DENSITY = 24.0;
    private static final String WINDY_PARTICLE = "xyz.bonfiremc.windy.particle.WindParticle";

    private static volatile boolean owns = false;
    private static volatile float strength = 0.0f;
    private static volatile float yaw = 0.0f;
    private static volatile double dirX = 0.0;
    private static volatile double dirZ = 1.0;
    private static volatile double speed = SPEED_BASE;
    private static volatile double density = 0.0;
    private static volatile double trainVx = 0.0;
    private static volatile double trainVz = 0.0;

    private WindyDrive() {}

    /** True while DT is driving Windy (a train world, in the Overworld). Calm still counts — that's the point. */
    public static boolean owns() {
        return owns;
    }

    public static float strength() {
        return strength;
    }

    /** Minecraft yaw degrees the wind blows toward (Windy moves particles by {@code -sin(yaw), cos(yaw)}). */
    public static float yawDegrees() {
        return yaw;
    }

    /** Streak velocity along X relative to the carriage (Windy also turns the sprite from this). */
    public static double velocityX(boolean strong) {
        return dirX * speed * (strong ? STRONG_SPEED : 1.0);
    }

    public static double velocityZ(boolean strong) {
        return dirZ * speed * (strong ? STRONG_SPEED : 1.0);
    }

    /** Multiplier on Windy's base spawn chance. */
    public static double spawnRate() {
        return density;
    }

    /** Carriage velocity added to streaks (blocks/tick), for the debug panel. */
    public static double trainVx() {
        return trainVx;
    }

    public static double trainVz() {
        return trainVz;
    }

    public static double streakSpeed() {
        return speed;
    }

    public static double density() {
        return density;
    }

    static void set(double push, double headingRad, double carriageVx, double carriageVz) {
        strength = (float) Math.min(MAX_STRENGTH, Math.max(0.0, push / REFERENCE_PUSH));
        // heading h: (cos h, sin h); yaw y: (-sin y, cos y)  =>  y = h - 90°
        yaw = (float) (Math.toDegrees(headingRad) - 90.0);
        dirX = Math.cos(headingRad);
        dirZ = Math.sin(headingRad);
        speed = SPEED_BASE + Math.max(0.0, push) * SPEED_PER_PUSH;
        density = Math.min(MAX_DENSITY, Math.max(0.0, push) / DENSITY_PUSH);
        trainVx = carriageVx;
        trainVz = carriageVz;
        owns = true;
    }

    static void release() {
        owns = false;
        strength = 0.0f;
        density = 0.0;
    }

    /** Nether-orange under a Nether sky (blended with how far in), dark blue in the deep dark. */
    private static final float[] NETHER_TINT = {1.0f, 0.55f, 0.2f};
    private static final float[] DEEP_DARK_TINT = {0.15f, 0.22f, 0.6f};

    private static void tint(Particle particle, net.minecraft.client.multiplayer.ClientLevel level,
                             net.minecraft.world.phys.Vec3 pos) {
        if (games.brennan.dungeontrain.wind.WindZones.isDeepDark(level, net.minecraft.core.BlockPos.containing(pos))) {
            particle.setColor(DEEP_DARK_TINT[0], DEEP_DARK_TINT[1], DEEP_DARK_TINT[2]);
            return;
        }
        float n = (float) games.brennan.dungeontrain.wind.WindZones.netherAt(pos.x);
        if (n <= 0.0f) return;
        particle.setColor(1.0f + (NETHER_TINT[0] - 1.0f) * n, 1.0f + (NETHER_TINT[1] - 1.0f) * n,
                1.0f + (NETHER_TINT[2] - 1.0f) * n);
    }

    /**
     * A particle is entering the engine: if it is one of Windy's streaks and DT owns the wind, move it in the
     * carriage's frame and let it keep its speed.
     */
    public static void adopt(Particle particle) {
        if (!owns || !particle.getClass().getName().startsWith(WINDY_PARTICLE)) return;
        WindyParticleAccessor p = (WindyParticleAccessor) particle;
        double relVx = p.dungeontrain$getXd();
        double relVz = p.dungeontrain$getZd();
        p.dungeontrain$setXd(relVx + trainVx);
        p.dungeontrain$setZd(relVz + trainVz);
        p.dungeontrain$setFriction(1.0f);
        p.dungeontrain$setHasPhysics(false);
        // Wind visuals only where wind goes: end the streak where its path first enters a carriage or tunnel.
        net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return;
        net.minecraft.world.phys.Vec3 pos = particle.getPos();
        tint(particle, level, pos);
        int open = WindVisualShelter.openTicks(level, pos.x, pos.y, pos.z, relVx, relVz, trainVx, trainVz,
                particle.getLifetime());
        if (open <= 0) {
            particle.remove();
        } else if (open < particle.getLifetime()) {
            particle.setLifetime(open);
        }
    }
}
