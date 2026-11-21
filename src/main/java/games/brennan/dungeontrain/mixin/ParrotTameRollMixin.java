package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import games.brennan.dungeontrain.perks.tier1.Friendliness;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.animal.Parrot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Friendliness perk: the {@code random.nextInt(10) == 0} tame roll in {@code Parrot#mobInteract}
 * gets one extra roll per rank of the feeding player ({@link Friendliness#tameRoll}).
 */
@Mixin(Parrot.class)
public abstract class ParrotTameRollMixin {

    @ModifyExpressionValue(method = "mobInteract",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextInt(I)I"))
    private int dungeontrain$perkTameRoll(int original, Player player, InteractionHand hand) {
        return Friendliness.tameRollFor(original, 10, player, ((Parrot) (Object) this).getRandom());
    }
}
