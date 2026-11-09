package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.train.TrainMembership;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Train villagers keep the one stock they spawned with: their trades never come back by themselves.
 *
 * <p>Vanilla's only automatic restock is {@code WorkAtPoi}, which asks {@link Villager#shouldRestock()}
 * before calling {@link Villager#restock()}. {@code shouldRestock} also runs the daily
 * {@code resetNumberOfRestocks} → {@code catchUpDemand}, which resets every offer's uses, so returning
 * before its body stops both. {@code restock()} itself is left alone, so a deliberate restock (the
 * Restock Villager perk) still works. {@code canRestock} goes to the trade screen, where {@code true}
 * shows the "restock up to two times per day" tooltip on sold-out trades.</p>
 */
@Mixin(Villager.class)
public abstract class TrainVillagerNoRestockMixin {

    @Inject(method = "shouldRestock", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$noAutoRestockOnTrain(CallbackInfoReturnable<Boolean> cir) {
        if (TrainMembership.isOnTrain((Villager) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "canRestock", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$noRestockTooltipOnTrain(CallbackInfoReturnable<Boolean> cir) {
        if (TrainMembership.isOnTrain((Villager) (Object) this)) cir.setReturnValue(false);
    }
}
