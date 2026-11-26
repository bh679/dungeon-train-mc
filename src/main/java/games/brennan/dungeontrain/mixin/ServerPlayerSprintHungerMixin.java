package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.effects.speedster.SpeedsterPerks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Sprint Hunger perk, movement half: scales the per-metre exhaustion {@code ServerPlayer.checkMovementStatistics}
 * charges by {@link SpeedsterPerks#sprintHungerFactor}, only while sprinting. That method calls
 * {@code causeFoodExhaustion} for swimming, climbing, walking and sprinting alike, so the sprint check lives in the
 * handler rather than the target selector; walking and swimming without sprint keep their vanilla cost.
 * The sprint-jump cost is {@link PlayerSprintHungerMixin}.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerSprintHungerMixin {

    @ModifyArg(method = "checkMovementStatistics(DDD)V",
            at = @At(value = "INVOKE", target = "causeFoodExhaustion(F)V"))
    private float dungeontrain$sprintHungerMove(float exhaustion) {
        return SpeedsterPerks.scaleSprintExhaustion((Player) (Object) this, exhaustion);
    }
}
