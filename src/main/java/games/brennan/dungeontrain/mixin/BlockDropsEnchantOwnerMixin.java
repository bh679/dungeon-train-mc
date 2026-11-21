package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.effects.crafter.EnchantContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enchant Bonus reach for Fortune and Silk Touch: block loot reads the tool's enchantments through
 * loot functions with no holder in sight. The breaking entity is pushed as owner around
 * {@code dropResources}.
 */
@Mixin(Block.class)
public abstract class BlockDropsEnchantOwnerMixin {

    private static final String DROP_RESOURCES =
        "dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
        + "Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V";

    @Inject(method = DROP_RESOURCES, at = @At("HEAD"))
    private static void dungeontrain$enterDrops(BlockState state, Level level, BlockPos pos, BlockEntity blockEntity,
                                                Entity entity, ItemStack tool, CallbackInfo ci) {
        EnchantContext.pushOwner(entity);
    }

    @Inject(method = DROP_RESOURCES, at = @At("RETURN"))
    private static void dungeontrain$leaveDrops(BlockState state, Level level, BlockPos pos, BlockEntity blockEntity,
                                                Entity entity, ItemStack tool, CallbackInfo ci) {
        EnchantContext.popOwner();
    }
}
