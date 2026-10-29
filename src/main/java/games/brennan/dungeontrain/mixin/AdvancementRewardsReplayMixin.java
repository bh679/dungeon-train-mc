package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.advancement.AdvancementProfiles;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skip an advancement's rewards while {@link AdvancementProfiles#replay} restores the cross-world
 * profile onto a world's tree. Vanilla {@code PlayerAdvancements.award} grants the rewards whenever an
 * advancement completes, and a replay completes every one the player has ever earned, so without this
 * each new world paid them all again: Biomes O' Plenty's all-biomes music disc in the hand at every
 * spawn, Uneasy Alliance's experience on every join. A genuine earn is untouched.
 */
@Mixin(AdvancementRewards.class)
public abstract class AdvancementRewardsReplayMixin {

    @Inject(method = "grant", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$skipOnReplay(ServerPlayer player, CallbackInfo ci) {
        if (AdvancementProfiles.isRestoringEarned()) {
            ci.cancel();
        }
    }
}
