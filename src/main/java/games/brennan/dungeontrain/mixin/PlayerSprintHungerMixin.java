package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.effects.speedster.SpeedsterPerks;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Sprint Hunger perk, jump half: scales the exhaustion a sprint-jump charges ({@code Player.jumpFromGround}) by
 * {@link SpeedsterPerks#sprintHungerFactor}. Plain jumps keep their vanilla cost. The per-metre sprint cost is in
 * {@link ServerPlayerSprintHungerMixin}. Vanilla's {@code causeFoodExhaustion} is already a no-op on the client,
 * so this costs nothing there.
 */
@Mixin(Player.class)
public abstract class PlayerSprintHungerMixin {

    @ModifyArg(method = "jumpFromGround()V",
            at = @At(value = "INVOKE",
                    target = "causeFoodExhaustion(F)V"))
    private float dungeontrain$sprintHungerJump(float exhaustion) {
        return dungeontrain$scaleSprintHunger(exhaustion);
    }

    private float dungeontrain$scaleSprintHunger(float exhaustion) {
        return SpeedsterPerks.scaleSprintExhaustion((Player) (Object) this, exhaustion);
    }
}
