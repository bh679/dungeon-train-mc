package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.block.VaultGreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultSharedData;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Vanilla picks a vault's flame from {@code ominous} alone (small flame or soul flame), in its idle,
 * activation and deactivation particles. A green vault ({@link VaultGreen}) burns {@link VaultGreen#FLAME}
 * instead. The smoke and connection particles stay vanilla.
 */
@Mixin(VaultBlockEntity.Client.class)
public abstract class VaultClientParticlesMixin {

    @ModifyVariable(method = "emitIdleParticles", at = @At("HEAD"), argsOnly = true)
    private static ParticleOptions dungeontrain$greenIdle(ParticleOptions particle, Level level, BlockPos pos,
                                                        VaultSharedData sharedData, ParticleOptions original) {
        return VaultGreen.isGreenAt(level, pos) ? VaultGreen.FLAME : particle;
    }

    @ModifyVariable(method = "emitActivationParticles", at = @At("HEAD"), argsOnly = true)
    private static ParticleOptions dungeontrain$greenActivation(ParticleOptions particle, Level level, BlockPos pos,
                                                              BlockState state, VaultSharedData sharedData,
                                                              ParticleOptions original) {
        return VaultGreen.isGreen(state) ? VaultGreen.FLAME : particle;
    }

    @ModifyVariable(method = "emitDeactivationParticles", at = @At("HEAD"), argsOnly = true)
    private static ParticleOptions dungeontrain$greenDeactivation(ParticleOptions particle, Level level, BlockPos pos,
                                                                ParticleOptions original) {
        return VaultGreen.isGreenAt(level, pos) ? VaultGreen.FLAME : particle;
    }
}
