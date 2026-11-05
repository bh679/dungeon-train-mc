package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.difficulty.VaultStatBoost;
import games.brennan.dungeontrain.editor.VaultLoot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultConfig;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * A vault that carries a DT loot pool ({@link VaultLoot}) rewards from that pool, rolled for the
 * player unlocking it, and shows the pool's items spinning inside. A vault without one is left to
 * vanilla's loot table. Either way the reward's stats get the vault's boost ({@link VaultStatBoost}).
 * Everything else (the key check, taking the key, remembering who has been rewarded) stays vanilla.
 */
@Mixin(VaultBlockEntity.Server.class)
public abstract class VaultServerMixin {

    @Inject(method = "resolveItemsToEject", at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$rollPoolReward(ServerLevel level, VaultConfig config, BlockPos pos,
                                                    Player player, CallbackInfoReturnable<List<ItemStack>> cir) {
        VaultLoot.rollForPlayer(level.getBlockEntity(pos), player.getUUID(), level.registryAccess())
            .ifPresent(cir::setReturnValue);
    }

    /**
     * Hooked on the call rather than the method's return: a pool reward cancels the method at its
     * head above, and would skip a return hook. Covers pool and loot-table rewards alike.
     */
    @ModifyExpressionValue(method = "tryInsertKey", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/entity/vault/VaultBlockEntity$Server;resolveItemsToEject(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/block/entity/vault/VaultConfig;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;)Ljava/util/List;"))
    private static List<ItemStack> dungeontrain$boostRewardStats(List<ItemStack> rewards,
                                                                 @Local(argsOnly = true) BlockState state) {
        return VaultStatBoost.boosted(rewards, VaultStatBoost.tierOf(state));
    }

    @Inject(method = "getRandomDisplayItemFromLootTable", at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$showPoolItem(ServerLevel level, BlockPos pos, ResourceKey<LootTable> table,
                                                  CallbackInfoReturnable<ItemStack> cir) {
        VaultLoot.displayItem(level.getBlockEntity(pos), level.getRandom()).ifPresent(cir::setReturnValue);
    }
}
