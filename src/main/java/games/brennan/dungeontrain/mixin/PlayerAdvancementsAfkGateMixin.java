package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.advancement.AfkAdvancementGate;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuse every advancement criterion while the player is AFK — see {@link AfkAdvancementGate} for
 * the rule and its exemptions. Returning {@code false} is exactly what {@code award} does for an
 * already-granted criterion, so callers need no special case.
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsAfkGateMixin {

    @Shadow
    private ServerPlayer player;

    @Inject(method = "award", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$refuseWhileAfk(AdvancementHolder holder, String criterion,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (AfkAdvancementGate.shouldBlock(player, holder, criterion)) {
            cir.setReturnValue(false);
        }
    }
}
