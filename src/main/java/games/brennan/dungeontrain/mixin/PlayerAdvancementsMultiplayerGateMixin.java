package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.advancement.MultiplayerChallengeGate;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuse Challenges-tab criteria in multiplayer — see {@link MultiplayerChallengeGate} for the rule.
 * Returning {@code false} is exactly what {@code award} does for an already-granted criterion, so
 * callers need no special case.
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMultiplayerGateMixin {

    @Shadow
    private ServerPlayer player;

    @Inject(method = "award", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$refuseChallengesInMultiplayer(AdvancementHolder holder, String criterion,
                                                            CallbackInfoReturnable<Boolean> cir) {
        if (MultiplayerChallengeGate.shouldBlock(player, holder, criterion)) {
            cir.setReturnValue(false);
        }
    }
}
