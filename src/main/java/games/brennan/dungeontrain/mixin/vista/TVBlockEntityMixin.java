package games.brennan.dungeontrain.mixin.vista;

import games.brennan.dungeontrain.registry.ModItems;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A hopper never pulls the Live Feed Cassette out of a TV: the cassette is how the TV stays tuned, and
 * it is never a player's item (see {@code event.UnobtainableLiveItems}). Vista lets any face take the
 * slot; every other cassette still comes out as Vista ships it.
 */
@Mixin(value = TVBlockEntity.class, remap = false)
public abstract class TVBlockEntityMixin {

    @Inject(method = "canTakeItemThroughFace", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$keepLiveCassette(int slot, ItemStack stack, Direction side,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (stack.is(ModItems.LIVE_CASSETTE.get())) cir.setReturnValue(false);
    }
}
