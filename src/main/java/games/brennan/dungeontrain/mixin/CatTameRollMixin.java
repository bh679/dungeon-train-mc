package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import games.brennan.dungeontrain.perks.tier1.Friendliness;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.animal.Cat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Friendliness perk: the {@code random.nextInt(3) == 0} tame roll in {@code Cat#mobInteract}
 * gets one extra roll per rank of the feeding player ({@link Friendliness#tameRoll}).
 */
@Mixin(Cat.class)
public abstract class CatTameRollMixin {

    @ModifyExpressionValue(method = "tryToTame",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextInt(I)I"))
    private int dungeontrain$perkTameRoll(int original, Player player) {
        return Friendliness.tameRollFor(original, 3, player, ((Cat) (Object) this).getRandom());
    }
}
