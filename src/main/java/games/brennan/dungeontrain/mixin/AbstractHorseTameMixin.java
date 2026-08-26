package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.player.RunTameTracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The horse half of {@link TamableAnimalTameMixin}. Horses, donkeys, mules, llamas and camels
 * descend from {@code AbstractHorse}, not {@code TamableAnimal}: they are tamed through
 * {@code tameWithName} and never raise NeoForge's {@code AnimalTameEvent}, so gentling a horse
 * would otherwise leave no trace on the run.
 */
@Mixin(AbstractHorse.class)
public abstract class AbstractHorseTameMixin {

    @Inject(method = "tameWithName", at = @At("TAIL"))
    private void dungeontrain$countTame(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof ServerPlayer serverPlayer) {
            RunTameTracker.record(serverPlayer, (AbstractHorse) (Object) this);
        }
    }
}
