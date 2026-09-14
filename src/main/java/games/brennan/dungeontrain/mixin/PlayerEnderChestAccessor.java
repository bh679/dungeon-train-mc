package games.brennan.dungeontrain.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets {@code EnderChestExpansion} swap a player's live Ender Chest container for a larger one.
 *
 * <p>{@code Player.enderChestInventory} is a plain protected field with no setter; vanilla only ever
 * assigns it at construction and in {@code ServerPlayer.restoreFrom}, which copies the reference across
 * a respawn — so a container swapped in here survives death and dimension changes for free.</p>
 */
@Mixin(Player.class)
public interface PlayerEnderChestAccessor {

    @Accessor("enderChestInventory")
    PlayerEnderChestContainer dungeontrain$getEnderChestInventory();

    @Accessor("enderChestInventory")
    void dungeontrain$setEnderChestInventory(PlayerEnderChestContainer container);
}
