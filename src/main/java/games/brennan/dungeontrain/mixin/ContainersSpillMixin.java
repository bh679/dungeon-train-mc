package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.compat.ContainerSpill;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks a container spill for {@link ContainerSpill}.
 *
 * <p>{@code Containers.dropItemStack} is where every vanilla container's contents become item entities when
 * it breaks: {@code dropContentsOnDestroy} (chests, barrels, hoppers, furnaces, decorated pots), the chiseled
 * bookshelf's own {@code onRemove}, and {@code chestVehicleDestroyed} (chest minecarts and boats). The
 * {@code EntityJoinLevelEvent} fires inside it, so {@code StartingBookEvents} can let a spilled photograph
 * land rather than burn.</p>
 */
@Mixin(Containers.class)
public abstract class ContainersSpillMixin {

    @Inject(
            method = "dropItemStack(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"))
    private static void dungeontrain$beginSpill(Level level, double x, double y, double z, ItemStack stack,
                                                CallbackInfo ci) {
        ContainerSpill.begin();
    }

    @Inject(
            method = "dropItemStack(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)V",
            at = @At("RETURN"))
    private static void dungeontrain$endSpill(Level level, double x, double y, double z, ItemStack stack,
                                              CallbackInfo ci) {
        ContainerSpill.end();
    }
}
