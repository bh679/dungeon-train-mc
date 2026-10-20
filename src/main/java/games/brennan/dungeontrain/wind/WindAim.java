package games.brennan.dungeontrain.wind;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.joml.primitives.AABBdc;

/**
 * Mobs that shoot (skeletons, pillagers, drowned with tridents, witches…) aim off for the wind.
 *
 * <p>When a mob's projectile enters the world, its launch velocity is corrected for the wind <b>the shooter is
 * standing in right now</b> — the steady wind and any gust already blowing — but not for gusts still to come,
 * which it cannot know. A steady wind is then beaten every time, and a gust arriving mid-flight still throws
 * the shot. Players get no help: they aim for themselves.</p>
 *
 * <p>The maths: the wind accelerates the projectile by {@code a} (from {@link WindPush#accel}, so the same
 * weight and category rules) for the flight time {@code t ≈ horizontal distance / horizontal speed}, drifting
 * it {@code ½·a·t²}. Starting it with {@code −½·a·t} along the wind cancels that drift on arrival.</p>
 *
 * <p>Only when the shooter is out in the wind on the train (the cheap {@link FarWind} test) — a skeleton
 * shooting down the inside of an enclosed carriage aims straight.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WindAim {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Longest flight we correct for, ticks — beyond this the guess is worthless anyway. */
    private static final double MAX_FLIGHT_TICKS = 80.0;
    private static final double MIN_HORIZONTAL_SPEED = 0.05;

    private WindAim() {}

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;
        if (!(event.getEntity() instanceof Projectile p) || p instanceof AbstractHurtingProjectile) return;
        if (!(p.getOwner() instanceof Mob shooter)) return;
        LivingEntity target = shooter.getTarget();
        if (target == null) return;
        WindServer.State s = WindServer.state();
        if (!s.active()) return;

        WindSample w = s.sampleAt(shooter.getX(), level.getGameTime())
                .scaled(WindZones.deepDarkFactor(level, shooter.blockPosition()));
        if (w.isCalm() || !shooterInWind(level, shooter)) return;

        Vec3 v = p.getDeltaMovement();
        double horizontal = Math.sqrt(v.x * v.x + v.z * v.z);
        if (horizontal < MIN_HORIZONTAL_SPEED) return;
        double dx = target.getX() - p.getX();
        double dz = target.getZ() - p.getZ();
        double t = Math.min(MAX_FLIGHT_TICKS, Math.sqrt(dx * dx + dz * dz) / horizontal);
        double a = WindPush.accel(p, w, s.profile(), 1.0);
        if (a <= 0.0) return;
        double lead = 0.5 * a * t;
        p.setDeltaMovement(v.x - w.dirX() * lead, v.y, v.z - w.dirZ() * lead);
        LOGGER.debug("[DungeonTrain] wind aim: {} leads its {} by {} b/t against wind {} toward {}° (flight ~{}t)",
                shooter.getType().toShortString(), p.getType().toShortString(), String.format("%.3f", lead),
                String.format("%.4f", w.strength()), String.format("%.0f", Math.toDegrees(w.headingRad())),
                String.format("%.0f", t));
    }

    private static boolean shooterInWind(ServerLevel level, Entity shooter) {
        for (Trains.Carriage c : Trains.allCarriages(level)) {
            AABBdc bb = c.ship().worldAABB();
            if (shooter.getX() < bb.minX() - 1 || shooter.getX() > bb.maxX() + 1) continue;
            if (shooter.getZ() < bb.minZ() - 1 || shooter.getZ() > bb.maxZ() + 1) continue;
            if (shooter.getY() < bb.minY() - 1 || shooter.getY() > bb.maxY() + 4) continue;
            return FarWind.exposed(c, shooter);
        }
        return false;
    }
}
