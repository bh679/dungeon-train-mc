package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.ship.sable.PhysicsFreeze;
import games.brennan.dungeontrain.ship.sable.SableEntityCarry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Mobs don't die from being clipped by a carriage, and every such hit is logged.
 *
 * <p>Sable's {@code LivingEntityMixin#sable$computeCollisionEffects} hurts any living entity whose
 * first sideways contact with a sub-level it isn't riding closes faster than 0.15 blocks/tick, for
 * {@code magnitude * 12 - 8}, using vanilla's {@code fly_into_wall} damage ("experienced kinetic
 * energy"). At DT's ~5 b/s train speed that deals nothing; a one-hit kill needs ~47 b/s, so the
 * deaths come from a carriage whose pose jumps in a single tick (a spawn, unpark or re-anchor
 * teleport). Until that spike is found, the damage is cancelled for every non-player.</p>
 *
 * <p>Vanilla deals the same damage only to an entity that is fall-flying (elytra into a wall), so
 * {@code !isFallFlying()} tells Sable's hit apart and genuine elytra crashes are left alone.
 * Players are logged but still take the damage.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SableImpactDamageGuard {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How far past the entity's box a sub-level's world box may sit and still be listed as a suspect. */
    private static final double SUSPECT_MARGIN = 2.0;

    private SableImpactDamageGuard() {}

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) return;
        boolean flyIntoWall = event.getSource().is(DamageTypes.FLY_INTO_WALL);
        if (!isSableImpact(flyIntoWall, entity.isFallFlying())) return;

        boolean cancel = shouldCancel(flyIntoWall, entity.isFallFlying(), entity instanceof Player);
        if (cancel) event.setCanceled(true);

        try {
            LOGGER.info("[DungeonTrain] [sable-impact] {} cancelled={}", describe(level, entity, event.getAmount()), cancel);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] [sable-impact] cancelled={} (diagnostics failed for {})", cancel, entity, e);
        }
    }

    /** {@code fly_into_wall} on an entity that isn't gliding can only have come from Sable. */
    static boolean isSableImpact(boolean flyIntoWall, boolean fallFlying) {
        return flyIntoWall && !fallFlying;
    }

    /** Cancel Sable's impact damage for everything except players. */
    static boolean shouldCancel(boolean flyIntoWall, boolean fallFlying, boolean isPlayer) {
        return isSableImpact(flyIntoWall, fallFlying) && !isPlayer;
    }

    private static String describe(ServerLevel level, LivingEntity entity, float amount) {
        StringBuilder sb = new StringBuilder();
        sb.append(entity.getType().builtInRegistryHolder().key().location())
            .append(" '").append(entity.getName().getString()).append("' id=").append(entity.getId())
            .append(" pos=").append(vec(entity.position()))
            .append(String.format(Locale.ROOT, " dmg=%.2f hp=%.2f", amount, entity.getHealth()))
            .append(" delta=").append(vec(entity.getDeltaMovement()));
        if (entity instanceof LivingEntityMovementExtension moving) {
            sb.append(" inherited=").append(vec(moving.sable$getInheritedVelocity()));
        }
        sb.append(" carrier=").append(SableEntityCarry.carrierName(entity));
        sb.append(" suspects=[").append(suspects(level, entity)).append(']');
        return sb.toString();
    }

    /**
     * Every sub-level whose world box is within {@link #SUSPECT_MARGIN} of the entity: its pose step
     * this tick (b/s — a teleport shows up as a huge value), its body's point velocity at the entity,
     * and whether DT has it parked. Only runs on a hit, so the full scan is fine.
     */
    private static String suspects(ServerLevel level, LivingEntity entity) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return "no-container";
        AABB box = entity.getBoundingBox().inflate(SUSPECT_MARGIN);
        Vector3d entityPos = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
        long gameTick = level.getGameTime();
        StringBuilder sb = new StringBuilder();
        for (ServerSubLevel sub : container.getAllSubLevels()) {
            if (sub.isRemoved()) continue;
            BoundingBox3dc b = sub.boundingBox();
            if (b.maxX() < box.minX || b.minX() > box.maxX
                || b.maxY() < box.minY || b.minY() > box.maxY
                || b.maxZ() < box.minZ || b.minZ() > box.maxZ) continue;
            if (!sb.isEmpty()) sb.append("; ");
            Vector3dc now = sub.logicalPose().position();
            Vector3d step = new Vector3d(now).sub(sub.lastPose().position()).mul(20.0);
            sb.append(sub.getUniqueId())
                .append(" step=").append(vec(step))
                .append(" frozen=").append(PhysicsFreeze.isFrozen(sub));
            if (PhysicsFreeze.isFrozen(sub)) {
                sb.append(" parkedTicks=").append(PhysicsFreeze.parkedTicks(sub, gameTick))
                    .append(String.format(Locale.ROOT, " lag=%.2f", PhysicsFreeze.bodyLagBlocks(sub)));
            } else {
                Vector3d local = sub.logicalPose().transformPositionInverse(new Vector3d(entityPos));
                sb.append(" pointVel=").append(vec(Sable.HELPER.getVelocity(level, sub, local, new Vector3d())));
            }
        }
        return sb.isEmpty() ? "none" : sb.toString();
    }

    private static String vec(Vec3 v) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    private static String vec(Vector3dc v) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x(), v.y(), v.z());
    }
}
