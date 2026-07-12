package games.brennan.dungeontrain.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes beds explode when used inside the Nether transition band's core, exactly as they do in
 * the real Nether — even though the band is still the <b>overworld</b> dimension (where
 * {@code dimensionType().bedWorks()} is {@code true}).
 *
 * <p>Vanilla decides explode-vs-sleep purely on the dimension's {@code bedWorks} flag
 * ({@link BedBlock#canSetSpawn}) inside {@code useWithoutItem}. We reproduce vanilla's exact
 * explosion branch — resolve the head half, remove both halves, detonate with the
 * {@code badRespawnPointExplosion} damage source — when the bed's <b>baked biome</b> at
 * {@code pos} is tagged {@link net.minecraft.tags.BiomeTags#IS_NETHER} (every core biome
 * {@code NetherCoreBiomes} assigns carries it). We gate on the persisted biome, not a live
 * band-formula recompute, so the explosion follows what the world actually generated and the
 * player sees — green highland/crossfade approach columns and config/build-drift columns are
 * not {@code IS_NETHER} and sleep normally. Server-side only (the explosion path is); the
 * {@code bedWorks()} guard means the real Nether/End keep their own handling and we only
 * <i>add</i> behaviour the overworld lacks.</p>
 */
@Mixin(BedBlock.class)
public abstract class BedBlockMixin {

    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$explodeInNetherCore(BlockState state, Level level, BlockPos pos, Player player,
                                                  BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (level.isClientSide) return;
        if (!level.dimensionType().bedWorks()) return;          // real Nether/End: vanilla already explodes
        if (!(level instanceof ServerLevel serverLevel)) return;
        // Gate on the bed's ACTUAL baked biome — what the world generated and the player sees —
        // not a live band-formula recompute. Only the real Nether core carries an IS_NETHER biome
        // (all five via NetherCoreBiomes); the green highland/crossfade approach and any
        // config/build-drift columns are not IS_NETHER, so beds sleep there as in vanilla.
        if (!serverLevel.getBiome(pos).is(BiomeTags.IS_NETHER)) return;

        Block self = (Block) (Object) this;
        BlockState headState = state;
        BlockPos headPos = pos;
        if (headState.getValue(BedBlock.PART) != BedPart.HEAD) {
            headPos = pos.relative(headState.getValue(HorizontalDirectionalBlock.FACING));
            headState = level.getBlockState(headPos);
            if (!headState.is(self)) {
                cir.setReturnValue(InteractionResult.CONSUME);
                return;
            }
        }

        level.removeBlock(headPos, false);
        BlockPos footPos = headPos.relative(headState.getValue(HorizontalDirectionalBlock.FACING).getOpposite());
        if (level.getBlockState(footPos).is(self)) {
            level.removeBlock(footPos, false);
        }

        Vec3 center = headPos.getCenter();
        level.explode(null, level.damageSources().badRespawnPointExplosion(center), null, center, 5.0F, true,
                Level.ExplosionInteraction.BLOCK);
        cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
