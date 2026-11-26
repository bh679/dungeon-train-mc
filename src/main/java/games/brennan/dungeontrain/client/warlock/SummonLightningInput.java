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
 * Summon Lightning's trigger: attack pressed with an empty hand while looking at a living entity within
 * {@link WarlockPerks#LIGHTNING_RANGE}, with no block in the way (torches and plants count, as vanilla
 * aims). The press becomes the cast (no punch) and goes to the server, which checks it again. A press
 * at a block in reach, at an armour stand, or at nothing, is left to vanilla.
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
        if (Perks.rank(player, WarlockPerks.SUMMON_LIGHTNING) <= 0) return;
        // a block in reach is for breaking, as vanilla has it
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) return;
        Entity target = lookedAt(player);
        if (target == null) return;
        DungeonTrainNet.sendToServer(new SummonLightningPacket(target.getId()));
        event.setSwingHand(true);
        event.setCanceled(true);
    }

    /** The living entity under the crosshair within range, stopping at the first block hit. */
    private static Entity lookedAt(LocalPlayer player) {
        double range = WarlockPerks.LIGHTNING_RANGE;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(range));
        BlockHitResult block = player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE, player));
        if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
        AABB sweep = player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, sweep,
            e -> e instanceof LivingEntity && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand) && e.isAlive() && !e.isSpectator() && e.isPickable(), eye.distanceToSqr(end));
        return hit == null ? null : hit.getEntity();
    }
}
