package games.brennan.dungeontrain.client.portal;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PortalPuppetAttackPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;

import java.util.Optional;

/**
 * Lets a swing find a puppet, and sends it to the server as a hit on the real thing.
 *
 * <p>A puppet is not in the client level, so nothing picks it: the crosshair looks straight through
 * to whatever is behind, and an attack breaks that block instead. This raycasts the drawn puppets
 * separately and, when one of them is what the player is actually looking at, takes the swing over.</p>
 *
 * <p><b>Strictly nearer, or hands it back.</b> The takeover only happens when the puppet is closer
 * than whatever {@code Minecraft.hitResult} found, so a swing meant for a block, a real mob, or empty
 * air behaves exactly as it always did. Getting this backwards would mean a puppet in the corner of
 * the room eating every swing in the corridor.</p>
 *
 * <p><b>Feedback is local and optimistic.</b> The real target takes its damage forty-odd blocks away,
 * where the attacker can neither see it flinch nor hear it — so without a flash and a swing here, a
 * landed hit would read as a miss. The puppet's health follows from the source's synched data on the
 * next snapshot either way, so a hit the server refuses corrects itself within a tick.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PortalPuppetPicking {

    /** How long the hurt flash lasts, matching the duration vanilla gives a real hit. */
    private static final int HURT_FLASH_TICKS = 10;

    private PortalPuppetPicking() {}

    /**
     * How charged the swing must be to land on a puppet.
     *
     * <p>This event fires on a click <b>and</b> every tick the button is held down over a block — so
     * holding attack while looking at a puppet in front of a wall would otherwise send twenty packets
     * a second. Gating on the attack-strength meter turns a held button into hits at the weapon's own
     * rate, which is what the meter is for. Slightly under 1 so a click a tick early is not swallowed
     * by client/server drift; the server scales the damage by its own reading regardless.</p>
     */
    private static final float MIN_ATTACK_STRENGTH = 0.9F;

    @SubscribeEvent
    public static void onAttackInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) return;
        if (PortalPuppetsClient.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isSpectator()) return;
        if (player.getAttackStrengthScale(0.0F) < MIN_ATTACK_STRENGTH) return;

        PortalPuppetsClient.Puppet puppet = pick(mc, player);
        if (puppet == null) return;

        // Ours now — vanilla would otherwise spend this click on whatever is behind the puppet.
        event.setCanceled(true);

        DungeonTrainNet.sendToServer(new PortalPuppetAttackPacket(puppet.entry().key()));
        player.swing(InteractionHand.MAIN_HAND);

        // Cancelling the vanilla interaction skips the reset it would have done, and an attack meter
        // stuck at full would let a held button fire every tick. Resetting here keeps the client's
        // meter in step with the server's, so the crosshair indicator reads true as well.
        player.resetAttackStrengthTicker();

        flash(puppet.model());
    }

    /**
     * The puppet under the crosshair, or {@code null} if the player is looking at something else.
     *
     * <p>Bounding boxes come free: the render pass positions every model each frame, so this reads
     * the same boxes the player was just looking at.</p>
     */
    private static PortalPuppetsClient.Puppet pick(Minecraft mc, LocalPlayer player) {
        double reach = player.entityInteractionRange();

        // Never reach past what is actually in front of the player. A puppet behind a wall is not a
        // target, and the wall is exactly what hitResult is reporting.
        double limit = reach;
        HitResult vanilla = mc.hitResult;
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS) {
            limit = Math.min(limit, vanilla.getLocation().distanceTo(player.getEyePosition()));
        }

        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(reach));

        PortalPuppetsClient.Puppet best = null;
        double bestDistance = limit;

        for (PortalPuppetsClient.Puppet candidate : PortalPuppetsClient.all()) {
            Entity model = candidate.model();

            // The same inflation vanilla gives an entity hitbox when picking, so a puppet is no
            // harder to hit than the entity it stands for.
            AABB box = model.getBoundingBox().inflate(model.getPickRadius());
            Optional<Vec3> hit = box.clip(eye, end);
            if (hit.isEmpty()) continue;

            double distance = eye.distanceTo(hit.get());
            if (distance >= bestDistance) continue;

            best = candidate;
            bestDistance = distance;
        }

        return best;
    }

    /** Flash the puppet red, the way a real entity flashes when it takes a hit. */
    private static void flash(Entity model) {
        if (!(model instanceof LivingEntity living)) return;
        living.hurtTime = HURT_FLASH_TICKS;
        living.hurtDuration = HURT_FLASH_TICKS;
    }
}
