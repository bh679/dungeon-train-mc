package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.compat.photo.VaultEjection;
import games.brennan.dungeontrain.loot.VaultFindStamper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.vault.VaultState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks a vault's reward eject for {@link VaultEjection}.
 *
 * <p>{@code ejectResultItem} is where every vault reward becomes an item entity, whether it was rolled from a
 * DT pool or vanilla's loot table. The {@code EntityJoinLevelEvent} fires inside it, so
 * {@code StartingBookEvents} can let a rewarded photograph land rather than burn. A rare find whose
 * number is already in is numbered here ({@link VaultFindStamper}).</p>
 */
@Mixin(VaultState.class)
public abstract class VaultEjectMixin {

    @Inject(method = "ejectResultItem", at = @At("HEAD"))
    private void dungeontrain$beginEject(ServerLevel level, BlockPos pos, ItemStack stack, float pitch,
                                         CallbackInfo ci) {
        VaultEjection.begin();
        VaultFindStamper.onEject(level, stack);
    }

    @Inject(method = "ejectResultItem", at = @At("RETURN"))
    private void dungeontrain$endEject(ServerLevel level, BlockPos pos, ItemStack stack, float pitch,
                                       CallbackInfo ci) {
        VaultEjection.end();
    }
}
