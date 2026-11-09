package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.difficulty.NewJobMendingBlock;
import games.brennan.dungeontrain.event.VillagerTrainSpawnEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla trade generation never sells Mending ({@link NewJobMendingBlock}): {@code updateTrades}
 * runs when a villager takes a job and on every level-up. HEAD notes how many offers there were,
 * TAIL swaps Mending books among the new ones, unless the train spawned this merchant with its job.
 * Reads the {@code offers} field directly: {@code getOffers()} would re-enter {@code updateTrades}
 * while it is still null.
 */
@Mixin(Villager.class)
public abstract class VillagerNewJobNoMendingMixin extends AbstractVillager {

    @Unique
    private int dungeontrain$offersBefore;

    private VillagerNewJobNoMendingMixin(EntityType<? extends AbstractVillager> type, Level level) {
        super(type, level);
    }

    @Inject(method = "updateTrades", at = @At("HEAD"))
    private void dungeontrain$countOffers(CallbackInfo ci) {
        dungeontrain$offersBefore = this.offers == null ? 0 : this.offers.size();
    }

    @Inject(method = "updateTrades", at = @At("TAIL"))
    private void dungeontrain$noMendingOnNewJob(CallbackInfo ci) {
        if (this.offers == null || this.level().isClientSide) return;
        if (NewJobMendingBlock.mayOfferMending(this, VillagerTrainSpawnEvents.REROLLED_TAG)) return;
        NewJobMendingBlock.replaceMendingBooks(this, this.offers, dungeontrain$offersBefore);
    }
}
