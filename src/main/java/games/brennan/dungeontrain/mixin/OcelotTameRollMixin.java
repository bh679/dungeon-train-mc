package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import games.brennan.dungeontrain.perks.tier1.Friendliness;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.animal.Ocelot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Friendliness perk: the {@code random.nextInt(3) == 0} tame roll in {@code Ocelot#mobInteract}
 * gets one extra roll per rank of the feeding player ({@link Friendliness#tameRoll}).
 */
@Mixin(Ocelot.class)
public abstract class OcelotTameRollMixin {

    @ModifyExpressionValue(method = "mobInteract",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextInt(I)I"))
    private int dungeontrain$perkTameRoll(int original, Player player, InteractionHand hand) {
        return Friendliness.tameRollFor(original, 3, player, ((Ocelot) (Object) this).getRandom());
    }
}
