package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.compat.photo.FramePhotoRemoval;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marks a frame entity's drop for {@link FramePhotoRemoval}.
 *
 * <p>Vanilla item frames and Exposure's {@code PhotographFrameEntity} (the floor and ceiling frames
 * {@code PhotoFrameBlockPlacer} leaves to Exposure) are both {@link HangingEntity HangingEntities}, and both
 * drop what they hold through {@code Entity.spawnAtLocation(ItemStack, float)}. The join event fires inside
 * it, so a photograph taken out of one lands rather than burns. Anything that is not a hanging entity
 * returns at the {@code instanceof} check.</p>
 */
@Mixin(Entity.class)
public abstract class HangingFrameDropMixin {

    @Inject(
            method = "spawnAtLocation(Lnet/minecraft/world/item/ItemStack;F)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"))
    private void dungeontrain$beginFrameDrop(ItemStack stack, float yOffset,
                                             CallbackInfoReturnable<ItemEntity> cir) {
        if ((Object) this instanceof HangingEntity) FramePhotoRemoval.begin();
    }

    @Inject(
            method = "spawnAtLocation(Lnet/minecraft/world/item/ItemStack;F)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("RETURN"))
    private void dungeontrain$endFrameDrop(ItemStack stack, float yOffset,
                                           CallbackInfoReturnable<ItemEntity> cir) {
        if ((Object) this instanceof HangingEntity) FramePhotoRemoval.end();
    }
}
