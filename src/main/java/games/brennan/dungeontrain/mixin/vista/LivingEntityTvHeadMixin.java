package games.brennan.dungeontrain.mixin.vista;

import net.mehvahdjukaar.vista.common.tv.TVBlock;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vista TVs are hats: a TV item belongs in the head slot, the way a carved pumpkin does, so it can be
 * shift-clicked or dragged there (or dispensed onto someone). Wearing one pins the live feed to the
 * wearer's screen — {@code client/live/LiveHeadViewer}. Right-clicking a TV still places it.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityTvHeadMixin {

    @Inject(method = "getEquipmentSlotForItem", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$tvOnHead(ItemStack stack, CallbackInfoReturnable<EquipmentSlot> cir) {
        if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof TVBlock) {
            cir.setReturnValue(EquipmentSlot.HEAD);
        }
    }
}
