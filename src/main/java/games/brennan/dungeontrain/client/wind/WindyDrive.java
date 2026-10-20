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
 *   <li><b>Past the cap</b>: wind that would want more than {@link #MAX_DENSITY} draws extra copies of each streak
 *       instead of spawning more ({@link #copiesFor}) — up to {@link #MAX_COPIES} per streak, rising smoothly. A
 *       copy costs one more quad to draw and no path check, which is where the cost of a streak is.</li>
 *   <li><b>Size</b>: each streak is {@link #MIN_SCALE}-{@link #MAX_SCALE}x, mostly small between gusts and mostly
 *       big in one.</li>
 *   <li><b>Distance</b>: streaks are placed further out between gusts and pulled in close as a gust arrives.</li>
 *   <li><b>Opacity</b>: every streak on screen firms up while a gust blows, from Windy's {@link #CALM_ALPHA} to
 *       {@link #GUST_ALPHA} with the gust's share of the wind, eased over a few ticks so it swells and fades.</li>
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
    static final double DENSITY_PUSH = 0.001;
    static final double MAX_DENSITY = 48.0;
    /** Most copies one streak is drawn as once the wind wants more than {@link #MAX_DENSITY} (4 = 4x the cap). */
    static final int MAX_COPIES = 4;
    /** Each streak's size is picked in this range: small between gusts, big in them. */
    static final float MIN_SCALE = 0.25f;
    static final float MAX_SCALE = 1.5f;
    /**
     * Pick skew, {@code roll^skew}: above 1 leans small, below 1 leans big. Between gusts most streaks are small
     * ({@link #CALM_SIZE_SKEW}); as a gust takes over the pick leans to the big end ({@link #GUST_SIZE_SKEW}).
     */
    private static final double CALM_SIZE_SKEW = 2.5;
    private static final double GUST_SIZE_SKEW = 0.4;
    /** Streak opacity with no gust (Windy's own) and in a wind that is all gust. */
    static final float CALM_ALPHA = 0.5f;
    static final float GUST_ALPHA = 0.95f;
    /** Share of the gap to the target opacity closed each tick (~0.5 s to settle). */
    private static final float ALPHA_EASE = 0.2f;
    /**
     * How far from its anchor near the player a streak is placed (Windy's own is 40-69, mostly lost in fog):
     * {@link #CALM_NEAR}-{@link #CALM_FAR} between gusts, pulled in to {@link #GUST_NEAR}-{@link #GUST_FAR} as a
     * gust takes over (eased with the opacity).
     */
    static final int CALM_NEAR = 20;
    static final int CALM_FAR = 50;
    static final int GUST_NEAR = 10;
    static final int GUST_FAR = 35;
    private static final String WINDY_PARTICLE = "xyz.bonfiremc.windy.particle.WindParticle";

    private static volatile boolean owns = false;
    private static volatile float strength = 0.0f;
    private static volatile float yaw = 0.0f;
    private static volatile double dirX = 0.0;
    private static volatile double dirZ = 1.0;
    private static volatile double speed = SPEED_BASE;
    private static volatile double density = 0.0;
    /** How far the wind wants past the cap: 0 at or under it, 1 = twice the cap (capped at MAX_COPIES - 1). */
    private static volatile double overflow = 0.0;
    private static volatile float alpha = CALM_ALPHA;
    /** Eased gust share 0..1: drives opacity and how close streaks spawn. */
    private static volatile float gust = 0.0f;
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

    static void set(double push, double gust01, double headingRad, double carriageVx, double carriageVz) {
        gust += ((float) Math.max(0.0, Math.min(1.0, gust01)) - gust) * ALPHA_EASE;
        alpha = CALM_ALPHA + (GUST_ALPHA - CALM_ALPHA) * gust;
        strength = (float) Math.min(MAX_STRENGTH, Math.max(0.0, push / REFERENCE_PUSH));
        // heading h: (cos h, sin h); yaw y: (-sin y, cos y)  =>  y = h - 90°
        yaw = (float) (Math.toDegrees(headingRad) - 90.0);
        dirX = Math.cos(headingRad);
        dirZ = Math.sin(headingRad);
        speed = SPEED_BASE + Math.max(0.0, push) * SPEED_PER_PUSH;
        double wanted = Math.max(0.0, push) / DENSITY_PUSH;
        density = Math.min(MAX_DENSITY, wanted);
        overflow = Math.min(MAX_COPIES - 1, Math.max(0.0, wanted / MAX_DENSITY - 1.0));
        trainVx = carriageVx;
        trainVz = carriageVz;
        owns = true;
    }

    static void release() {
        owns = false;
        strength = 0.0f;
        density = 0.0;
        overflow = 0.0;
        alpha = CALM_ALPHA;
        gust = 0.0f;
    }

    /** Nearest a new streak is placed from its anchor (blocks): closer the more a gust has taken over. */
    public static int spawnNear() {
        return Math.round(CALM_NEAR + (GUST_NEAR - CALM_NEAR) * gust);
    }

    /** Spread past {@link #spawnNear} a new streak may be placed (blocks). */
    public static int spawnSpread() {
        return Math.max(1, Math.round(CALM_FAR + (GUST_FAR - CALM_FAR) * gust) - spawnNear());
    }

    /** Opacity every streak draws with this frame (firmer in a gust). */
    public static float alpha() {
        return alpha;
    }

    /** How many copies the streak past the cap (1 = just itself), for the debug panel. */
    public static double copies() {
        return 1.0 + overflow;
    }

    /**
     * Copies for one new streak: 1 + the whole part of the overflow, plus one more with the chance of its fraction,
     * so the average rises smoothly from 1x at the cap to {@link #MAX_COPIES}x.
     */
    static int copiesFor(double roll) {
        double o = overflow;
        int whole = (int) Math.floor(o);
        return Math.min(MAX_COPIES, 1 + whole + (roll < o - whole ? 1 : 0));
    }

    /**
     * Size for a new streak, {@link #MIN_SCALE}-{@link #MAX_SCALE}: mostly small between gusts, mostly big once a
     * gust has taken over ({@code gustShare} is the eased gust share 0..1, {@code roll} uniform 0..1).
     */
    static float scaleFor(float gustShare, double roll) {
        double skew = CALM_SIZE_SKEW + (GUST_SIZE_SKEW - CALM_SIZE_SKEW) * Math.max(0.0, Math.min(1.0, gustShare));
        double u = Math.pow(roll, skew);
        return (float) (MIN_SCALE + (MAX_SCALE - MIN_SCALE) * u);
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
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        particle.scale(scaleFor(gust, r.nextDouble()));
        if (particle instanceof WindyStreakCopies copies) {
            copies.dungeontrain$setCopies(copiesFor(r.nextDouble()), r.nextLong());
        }
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
