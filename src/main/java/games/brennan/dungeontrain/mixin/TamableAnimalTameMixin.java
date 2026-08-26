package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.player.RunTameTracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts a wolf, cat, ocelot or parrot tamed this run into the player's run state, so it reaches
 * the death screen and the death report.
 *
 * <p>A mixin rather than NeoForge's {@code AnimalTameEvent} because that event is fired only from
 * the {@code TamableAnimal} tame paths — horses never raise it (see {@link AbstractHorseTameMixin}).
 * Hooking the two tame methods directly is the only way to count <em>every</em> animal a player
 * tames with one mechanism.</p>
 */
@Mixin(TamableAnimal.class)
public abstract class TamableAnimalTameMixin {

    @Inject(method = "tame", at = @At("TAIL"))
    private void dungeontrain$countTame(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer) {
            RunTameTracker.record(serverPlayer, (TamableAnimal) (Object) this);
        }
    }
}
