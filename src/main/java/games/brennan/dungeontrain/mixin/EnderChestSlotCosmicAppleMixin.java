package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.item.CosmicAppleItem;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cosmic Apples (every tier) are perk points for this life, and the Ender Chest carries over between
 * lives, so no Ender Chest slot takes one. Click, drag and shift-click all ask {@link Slot#mayPlace};
 * DT's larger Ender Chests are the same container type behind a vanilla chest menu. Runs on both sides
 * so the client never shows a placement the server refuses. Apples already inside can still be taken out.
 */
@Mixin(Slot.class)
public abstract class EnderChestSlotCosmicAppleMixin {

    @Shadow @Final public Container container;

    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$noCosmicApplesInEnderChest(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (container instanceof PlayerEnderChestContainer && CosmicAppleItem.isCosmicApple(stack)) {
            cir.setReturnValue(false);
        }
    }
}
