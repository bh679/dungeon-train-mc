package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.block.VaultGreen;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives the vanilla vault DT's third type: a {@link VaultGreen#GREEN} property beside vanilla's
 * {@code ominous}. A vault saved or templated before it existed loads as {@code green=false}, so
 * every existing vault keeps its type.
 */
@Mixin(VaultBlock.class)
public abstract class VaultBlockMixin extends BaseEntityBlock {

    private VaultBlockMixin(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Inject(method = "createBlockStateDefinition", at = @At("TAIL"))
    private void dungeontrain$addGreen(StateDefinition.Builder<Block, BlockState> builder, CallbackInfo ci) {
        builder.add(VaultGreen.GREEN);
    }

    // A boolean property's first value is true, so the default state has to say false.
    @Inject(method = "<init>", at = @At("TAIL"))
    private void dungeontrain$defaultNotGreen(BlockBehaviour.Properties properties, CallbackInfo ci) {
        registerDefaultState(defaultBlockState().setValue(VaultGreen.GREEN, false));
    }
}
