package games.brennan.dungeontrain.client.wind;

import games.brennan.dungeontrain.wind.WindSample;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Snow you can see coming at you. The snow sheets can only carry flakes sideways across their face
 * ({@link AmbientWind#snowScrollU}), so looking into the wind the snow seems to stand still. A few real snowflake
 * particles near the camera fill that in: spawned mostly upwind, already moving with the wind (they drift like any
 * other blown particle), so they fly past and at you.
 *
 * <p>Kept small: only close to the camera, a handful per tick at most, scaled by the wind and how hard it is
 * snowing, halved on Decreased particles and off on Minimal. Only where it is actually snowing in the open (the
 * column's precipitation is snow, nothing overhead, not inside a carriage).</p>
 */
final class WindSnowflakes {

    /** Flakes per tick per unit of push, and the cap. A late steady wind (~0.014) gives ~2 a tick, gusts the cap. */
    static final double RATE_PER_PUSH = 150.0;
    static final double MAX_PER_TICK = 4.0;
    /** Placement around the camera, blocks: how far upwind, how wide across, how far below/above. */
    private static final double UPWIND_MAX = 8.0;
    private static final double SPREAD = 7.0;
    private static final double BELOW = 2.0;
    private static final double ABOVE = 6.0;

    private WindSnowflakes() {}

    static void tick(Minecraft mc, ClientLevel level, WindSample visual) {
        if (visual.isCalm() || !level.isRaining()) return;
        ParticleStatus status = mc.options.particles().get();
        if (status == ParticleStatus.MINIMAL) return;
        double expected = flakesPerTick(visual.strength(), level.getRainLevel(1.0f));
        if (status == ParticleStatus.DECREASED) expected *= 0.5;
        if (expected <= 0.0) return;
        RandomSource r = level.random;
        int n = (int) expected + (r.nextDouble() < expected - Math.floor(expected) ? 1 : 0);
        if (n == 0) return;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        double dx = visual.dirX();
        double dz = visual.dirZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < n; i++) {
            double back = r.nextDouble() * UPWIND_MAX;
            double side = (r.nextDouble() * 2.0 - 1.0) * SPREAD;
            double x = cam.x - dx * back - dz * side;
            double z = cam.z - dz * back + dx * side;
            double y = cam.y - BELOW + r.nextDouble() * (BELOW + ABOVE);
            pos.set(x, y, z);
            if (!snowingAt(level, pos) || AmbientWind.sheltered(level, x, y, z)) continue;
            Particle p = mc.particleEngine.createParticle(ParticleTypes.SNOWFLAKE, x, y, z, 0.0, -0.05, 0.0);
            if (p instanceof WindDriftParticle drift) drift.dungeontrain$primeDrift();
        }
    }

    /** Expected flakes this tick for a push and rain level (0..1). */
    static double flakesPerTick(double push, float rainLevel) {
        return Math.min(MAX_PER_TICK, Math.max(0.0, push) * RATE_PER_PUSH) * Math.max(0.0f, Math.min(1.0f, rainLevel));
    }

    private static boolean snowingAt(ClientLevel level, BlockPos pos) {
        if (pos.getY() < level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ())) return false;
        return level.getBiome(pos).value().getPrecipitationAt(pos) == Biome.Precipitation.SNOW;
    }
}
