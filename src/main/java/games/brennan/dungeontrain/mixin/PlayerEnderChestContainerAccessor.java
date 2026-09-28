package games.brennan.dungeontrain.mixin;

import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the block entity a live Ender Chest container is currently opened through, so
 * {@code EnderChestExpansion} can carry it over to the replacement container — that reference is what
 * closes the menu when the player walks away and drives the lid animation, and vanilla exposes only an
 * {@code isActiveChest(...)} equality test, not a getter.
 */
@Mixin(PlayerEnderChestContainer.class)
public interface PlayerEnderChestContainerAccessor {

    @Accessor("activeChest")
    EnderChestBlockEntity dungeontrain$getActiveChest();
}
