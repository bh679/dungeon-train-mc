package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.train.CarriagePets;
import net.minecraft.world.entity.TamableAnimal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A pet catching up with an owner who rides a carriage lands on the deck beside them, not in the
 * carriage floor. See {@link CarriagePets#followOntoDeck}.
 *
 * <p>Hooks the public {@code tryToTeleportToOwner} because the spot-picking methods under it are
 * private; it is also the one entry every caller uses ({@code FollowOwnerGoal}, the parrot's
 * follow, {@link CarriagePets}' own send-home).</p>
 */
@Mixin(TamableAnimal.class)
public abstract class TamableAnimalFollowTeleportMixin {

    @Inject(method = "tryToTeleportToOwner", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$followOntoDeck(CallbackInfo ci) {
        if (CarriagePets.followOntoDeck((TamableAnimal) (Object) this)) {
            ci.cancel();
        }
    }
}
