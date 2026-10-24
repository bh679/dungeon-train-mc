package games.brennan.dungeontrain.perks.effects.speedster;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Event hooks for the Speedster perks whose effect is a plain event tweak: Haste (break speed), Sprint Speed
 * (a transient movement-speed modifier held only while sprinting) and Sprint Jump (extra impulse on a sprint
 * jump). Sprint Hunger is a mixin ({@code PlayerSprintHungerMixin}) and Wind Resistance sits in
 * {@code wind/WindPush}. All three run on both sides where the vanilla code does; they read the same synced
 * rank, so client prediction and server truth agree. Every handler is a no-op at rank 0.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SpeedsterEvents {

    /** Our sprint-speed modifier; separate from vanilla's sprint modifier so the two never fight. */
    static final ResourceLocation SPRINT_SPEED_ID =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "perk_sprint_speed");

    private SpeedsterEvents() {}

    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        double m = SpeedsterPerks.hasteMultiplier(event.getEntity());
        if (m != 1.0) event.setNewSpeed((float) (event.getNewSpeed() * m));
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player p = event.getEntity();
        AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        double bonus = p.isSprinting() ? SpeedsterPerks.sprintSpeedBonus(p) : 0.0;
        AttributeModifier current = speed.getModifier(SPRINT_SPEED_ID);
        if (bonus <= 0.0) {
            if (current != null) speed.removeModifier(SPRINT_SPEED_ID);
            return;
        }
        if (current != null && current.amount() == bonus) return;
        speed.addOrUpdateTransientModifier(
                new AttributeModifier(SPRINT_SPEED_ID, bonus, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent event) {
        if (!(event.getEntity() instanceof Player p) || !p.isSprinting()) return;
        double impulse = SpeedsterPerks.sprintJumpImpulse(p);
        if (impulse <= 0.0) return;
        float yaw = p.getYRot() * Mth.DEG_TO_RAD;
        p.addDeltaMovement(new Vec3(-Mth.sin(yaw) * impulse, 0.0, Mth.cos(yaw) * impulse));
    }
}
