package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.effects.lover.PetHealthEffects;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Horses, donkeys, mules, camels and llamas tame through {@code tameWithName} and never raise
 * {@code AnimalTameEvent}, so the Pet Health perk hooks the method directly (as
 * {@link AbstractHorseTameMixin} does for the run tame counter).
 */
@Mixin(AbstractHorse.class)
public abstract class AbstractHorsePerkTameMixin {

    @Inject(method = "tameWithName", at = @At("TAIL"))
    private void dungeontrain$perkPetHealth(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            PetHealthEffects.onTamed((AbstractHorse) (Object) this, player);
        }
    }
}
