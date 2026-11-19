package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.nap.HostileBandBeds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
 * Makes beds explode when used inside the Nether <b>or</b> End transition band's core, exactly as
 * they do in the real Nether and End — even though both bands are still the <b>overworld</b>
 * dimension (where {@code dimensionType().bedWorks()} is {@code true}).
 *
 * <p>Vanilla decides explode-vs-sleep purely on the dimension's {@code bedWorks} flag
 * ({@link BedBlock#canSetSpawn}) inside {@code useWithoutItem}. We reproduce vanilla's exact
 * explosion branch — resolve the head half, remove both halves, detonate with the
 * {@code badRespawnPointExplosion} damage source — when the bed's <b>baked biome</b> at
 * {@code pos} is tagged {@link net.minecraft.tags.BiomeTags#IS_NETHER} (every core biome
 * {@code NetherCoreBiomes} assigns carries it) or {@link net.minecraft.tags.BiomeTags#IS_END}
 * (every core biome {@code EndCoreBiomes} assigns — sampled from the real End's biome source, so
 * {@code the_end} and the four outer-island biomes — carries it). We gate on the persisted biome,
 * not a live band-formula recompute, so the explosion follows what the world actually generated
 * and the player sees — the green highland/crossfade approach columns and any config/build-drift
 * columns are neither {@code IS_NETHER} nor {@code IS_END} and sleep normally. Server-side only
 * (the explosion path is); the {@code bedWorks()} guard means the real Nether/End keep their own
 * handling and we only <i>add</i> behaviour the overworld lacks.</p>
 *
 * <p><b>Beds inside a carriage</b> are the one place the baked biome lies. A carriage is a Sable
 * sub-level, and Sable gives every sub-level plot a single biome frozen at assembly time from the
 * world biome under the ship's spawn pose ({@code ServerSubLevelContainer.createSubLevel} →
 * {@code plot.setBiome}). A carriage appended while the train is in the Nether core therefore
 * reports an {@code IS_NETHER} biome for every one of its blocks forever — 1,000 blocks later, back
 * in plain overworld, a bed placed on its floor still read as Nether and detonated (#1448). So when
 * {@code pos} resolves to a carriage we sample the parent world at the bed's <em>current
 * world-space</em> position ({@code ManagedShip#shipToWorld}) instead: the bed follows where the
 * train actually is, sleeping in the overworld and still exploding while the train is inside a
 * band core. Ground beds are unaffected. The sampling lives in {@link HostileBandBeds}, which the nap
 * prompt shares so an exploding bed never offers a nap.</p>
 */
@Mixin(BedBlock.class)
public abstract class BedBlockMixin {

    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$explodeInHostileBand(BlockState state, Level level, BlockPos pos, Player player,
                                                   BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (level.isClientSide) return;
        if (!level.dimensionType().bedWorks()) return;          // real Nether/End: vanilla already explodes
        if (!(level instanceof ServerLevel serverLevel)) return;
        // Gate on the bed's ACTUAL baked biome at its live position (HostileBandBeds): what the world
        // generated and the player sees. The green highland/crossfade approach and any
        // config/build-drift columns are neither IS_NETHER nor IS_END, so beds sleep there as in vanilla.
        if (HostileBandBeds.coreAt(serverLevel, pos) == HostileBandBeds.Core.NONE) return;

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
