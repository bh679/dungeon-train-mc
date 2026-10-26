package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.effects.crafter.CrafterCookingOwner;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cooking Time ownership: the player who opens a furnace-family menu becomes the block's cook
 * ({@code AbstractFurnaceCookingTimeMixin}). The three-arg {@code createMenu} is the one place every
 * open passes through, on the container base class since the furnace's own override is abstract.
 */
@Mixin(BaseContainerBlockEntity.class)
public abstract class BaseContainerBlockEntityCookingOwnerMixin {

    @Inject(method = "createMenu(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/entity/player/Player;)"
                     + "Lnet/minecraft/world/inventory/AbstractContainerMenu;",
            at = @At("RETURN"))
    private void dungeontrain$rememberCook(int containerId, Inventory inventory, Player player,
                                           CallbackInfoReturnable<AbstractContainerMenu> cir) {
        if (cir.getReturnValue() == null) return;
        if ((Object) this instanceof CrafterCookingOwner owner) owner.dungeontrain$setCookingOwner(player);
    }
}
