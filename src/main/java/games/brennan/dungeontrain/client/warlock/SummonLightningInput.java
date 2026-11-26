package games.brennan.dungeontrain.client.warlock;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.effects.warlock.SummonLightningPacket;
import games.brennan.dungeontrain.perks.effects.warlock.WarlockPerks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * Sacrificial Lightning's trigger ({@link WarlockPerks#SUMMON_LIGHTNING}): attack pressed with an empty hand while looking at a living entity within
 * {@link WarlockPerks#LIGHTNING_RANGE}, with no block in the way (torches and plants count, as vanilla
 * aims). The press becomes the cast (no punch) and goes to the server, which checks it again. A press
 * at a block in reach, at an armour stand, or at nothing, is left to vanilla, and so is any press while
 * the cast would cost more health than the player has.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class SummonLightningInput {

    private SummonLightningInput() {}

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isSpectator() || !player.getMainHandItem().isEmpty()) return;
        int rank = Perks.rank(player, WarlockPerks.SUMMON_LIGHTNING);
        if (rank <= 0) return;
        // too weak to pay for it: just a punch, no message
        if (!player.getAbilities().instabuild && WarlockPerks.tooWeakToCast(rank, player.getHealth())) return;
        // a block in reach is for breaking, as vanilla has it
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) return;
        Entity target = lookedAt(player);
        if (target == null) return;
        DungeonTrainNet.sendToServer(new SummonLightningPacket(target.getId()));
        event.setSwingHand(true);
        event.setCanceled(true);
    }

    /**
     * The living entity under the crosshair within range, if no block stands between it and the eye.
     * Found along the full ray first, then a second ray from the eye to the hit decides whether a block is
     * in the way: on a Sable train a block hit comes back in the ship's plot coordinates, not the world's,
     * so its location can't be used to shorten the first ray, but whether there is one at all still holds.
     */
    private static Entity lookedAt(LocalPlayer player) {
        double range = WarlockPerks.LIGHTNING_RANGE;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(range));
        AABB sweep = player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, sweep,
            e -> e instanceof LivingEntity && !(e instanceof ArmorStand) && e.isAlive() && !e.isSpectator() && e.isPickable(),
            range * range);
        if (hit == null) return null;
        BlockHitResult block = player.level().clip(new ClipContext(eye, hit.getLocation(), ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE, player));
        return block.getType() == HitResult.Type.MISS ? hit.getEntity() : null;
    }
}
