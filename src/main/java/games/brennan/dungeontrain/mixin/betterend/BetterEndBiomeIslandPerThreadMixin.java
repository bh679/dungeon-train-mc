package games.brennan.dungeontrain.mixin.betterend;

import games.brennan.dungeontrain.worldgen.BiomeIslandState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.betterx.bclib.sdf.SDF;
import org.betterx.betterend.noise.OpenSimplexNoise;
import org.betterx.betterend.world.features.BiomeIslandFeature;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * BetterEnd's biome island keeps the island being placed — centre, noise, top and under block — in statics
 * that its {@code static} SDF reads while filling, and the SDF itself holds a scratch vector. Every read and
 * write of them goes to this thread's {@link BiomeIslandState} instead, and each placement starts from the
 * default top block rather than whatever the previous island left behind.
 *
 * <p>No biome in the pinned BetterEnd build places this feature, so this is insurance against a build that
 * does; it has not been exercised by world generation.</p>
 */
@Mixin(value = BiomeIslandFeature.class, remap = false)
public abstract class BetterEndBiomeIslandPerThreadMixin {

    private static final String PLACE = "place";
    private static final String BLOCK_LAMBDA = "lambda$createSDFIsland$0";
    private static final String NOISE_LAMBDA = "lambda$createSDFIsland$1";

    @Shadow(remap = false)
    private static SDF createSDFIsland() {
        throw new AssertionError();
    }

    @Inject(method = PLACE, at = @At("HEAD"))
    private void dungeontrain$startFromDefaultTop(CallbackInfoReturnable<Boolean> cir) {
        BiomeIslandState.get().topBlock = BiomeIslandState.defaultTopBlock();
    }

    @Redirect(method = { PLACE, BLOCK_LAMBDA, NOISE_LAMBDA }, at = @At(value = "FIELD",
        target = "CENTER:Lnet/minecraft/core/BlockPos$MutableBlockPos;", opcode = Opcodes.GETSTATIC))
    private static BlockPos.MutableBlockPos dungeontrain$center() {
        return BiomeIslandState.get().center;
    }

    @Redirect(method = PLACE, at = @At(value = "FIELD",
        target = "ISLAND:Lorg/betterx/bclib/sdf/SDF;", opcode = Opcodes.GETSTATIC))
    private static SDF dungeontrain$island() {
        return BiomeIslandState.get().island(BetterEndBiomeIslandPerThreadMixin::createSDFIsland);
    }

    @Redirect(method = NOISE_LAMBDA, at = @At(value = "FIELD",
        target = "simplexNoise:Lorg/betterx/betterend/noise/OpenSimplexNoise;", opcode = Opcodes.GETSTATIC))
    private static OpenSimplexNoise dungeontrain$noise() {
        return BiomeIslandState.get().noise;
    }

    @Redirect(method = PLACE, at = @At(value = "FIELD",
        target = "simplexNoise:Lorg/betterx/betterend/noise/OpenSimplexNoise;", opcode = Opcodes.PUTSTATIC))
    private static void dungeontrain$setNoise(OpenSimplexNoise noise) {
        BiomeIslandState.get().noise = noise;
    }

    @Redirect(method = BLOCK_LAMBDA, at = @At(value = "FIELD",
        target = "topBlock:Lnet/minecraft/world/level/block/state/BlockState;", opcode = Opcodes.GETSTATIC))
    private static BlockState dungeontrain$topBlock() {
        return BiomeIslandState.get().topBlock;
    }

    @Redirect(method = PLACE, at = @At(value = "FIELD",
        target = "topBlock:Lnet/minecraft/world/level/block/state/BlockState;", opcode = Opcodes.PUTSTATIC))
    private static void dungeontrain$setTopBlock(BlockState state) {
        BiomeIslandState.get().topBlock = state;
    }

    @Redirect(method = BLOCK_LAMBDA, at = @At(value = "FIELD",
        target = "underBlock:Lnet/minecraft/world/level/block/state/BlockState;", opcode = Opcodes.GETSTATIC))
    private static BlockState dungeontrain$underBlock() {
        return BiomeIslandState.get().underBlock;
    }

    @Redirect(method = PLACE, at = @At(value = "FIELD",
        target = "underBlock:Lnet/minecraft/world/level/block/state/BlockState;", opcode = Opcodes.PUTSTATIC))
    private static void dungeontrain$setUnderBlock(BlockState state) {
        BiomeIslandState.get().underBlock = state;
    }
}
