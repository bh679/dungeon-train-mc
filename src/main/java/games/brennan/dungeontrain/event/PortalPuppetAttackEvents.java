package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalPuppetAttack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;

/**
 * Points knockback the right way when a hit crossed the midpoint.
 *
 * <p>Most of an attack survives the mirror untouched. {@code Player.attack} even knocks back along
 * the attacker's <b>yaw</b>, and yaw crosses unchanged because both corridors are axis-aligned. But
 * {@code LivingEntity.hurt} applies a second knockback along {@code sourcePosition − targetPosition},
 * and those two positions are in different copies: the horizontal part of that vector is mostly
 * however far the twin has drifted from its carriage, up to two dozen blocks. A mob hit through a
 * portal would sail off in a direction with no relation to where it was hit from — sometimes
 * straight at the attacker.</p>
 *
 * <p>So the ratio is rewritten to point away from where the attacker <i>appears</i> to be standing,
 * in the copy the target is in. Only the direction: the strength is whatever vanilla decided, and
 * every knockback that is not part of one of our cross-midpoint attacks passes through untouched.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalPuppetAttackEvents {

    private PortalPuppetAttackEvents() {}

    @SubscribeEvent
    public static void onKnockBack(LivingKnockBackEvent event) {
        LivingEntity target = PortalPuppetAttack.inFlightTarget();
        if (target == null || event.getEntity() != target) return;

        Vec3 attacker = PortalPuppetAttack.inFlightAttackerMirrored();
        if (attacker == null) return;

        double dx = attacker.x - target.getX();
        double dz = attacker.z - target.getZ();

        // Dead centre — the attacker is standing exactly on the target in mirrored space. Any
        // direction is as good as any other, so leave vanilla's choice alone rather than normalising
        // a zero vector into a NaN.
        if (dx == 0.0 && dz == 0.0) return;

        event.setRatioX(dx);
        event.setRatioZ(dz);
    }
}
