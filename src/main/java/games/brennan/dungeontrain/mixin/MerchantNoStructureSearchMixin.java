package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.difficulty.StructureMapListings;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.VillagerTrades;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Drops explorer-map listings before vanilla picks a villager's or wandering trader's new trades
 * ({@code Villager#updateTrades} on taking a job and on every level-up, {@code WanderingTrader#updateTrades}).
 * Rolling one runs a 100-chunk structure search on the server thread, which froze the server in DT's world
 * ({@link StructureMapListings}). Vanilla's loop keeps drawing until it has its count, so the slot fills from
 * the rest of the pool.
 */
@Mixin(AbstractVillager.class)
public abstract class MerchantNoStructureSearchMixin {

    @ModifyVariable(method = "addOffersFromItemListings", at = @At("HEAD"), argsOnly = true)
    private VillagerTrades.ItemListing[] dungeontrain$noStructureSearch(VillagerTrades.ItemListing[] newTrades) {
        return StructureMapListings.withoutStructureSearch(newTrades);
    }
}
