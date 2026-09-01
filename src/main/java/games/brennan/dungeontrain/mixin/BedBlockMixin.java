package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.NetherBand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
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
 * columns are neither {@code IS_NETHER} nor {@code IS_END} and sleep normally.</p>
 *
 * <p>A Nether-band bed must additionally be
 * {@link games.brennan.dungeontrain.worldgen.NetherBand#CORE_DEPTH_BLOCKS} deep into the core (via
 * {@link games.brennan.dungeontrain.worldgen.NetherBand#isDeepInNetherCore}) — the very gate that
 * grants {@code reached_nether}, so the bed can never detonate before the "Entered the Nether"
 * advancement that warns the player. The End branch keeps the plain biome gate: its
 * {@code reached_end_islands} marker has no depth gate, so End beds already explode no earlier
 * than their advancement. Server-side only
 * (the explosion path is); the {@code bedWorks()} guard means the real Nether/End keep their own
 * handling and we only <i>add</i> behaviour the overworld lacks.</p>
 */
@Mixin(BedBlock.class)
public abstract class BedBlockMixin {

    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$explodeInHostileBand(BlockState state, Level level, BlockPos pos, Player player,
                                                   BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (level.isClientSide) return;
        if (!level.dimensionType().bedWorks()) return;          // real Nether/End: vanilla already explodes
        if (!(level instanceof ServerLevel serverLevel)) return;
        // Gate on the bed's ACTUAL baked biome — what the world generated and the player sees —
        // not a live band-formula recompute. Only the real Nether core carries an IS_NETHER biome
        // (all five via NetherCoreBiomes) and only the real End core carries an IS_END biome (via
        // EndCoreBiomes); the green highland/crossfade approach and any config/build-drift columns
        // are neither, so beds sleep there as in vanilla.
        Holder<Biome> biome = serverLevel.getBiome(pos);
        boolean nether = biome.is(BiomeTags.IS_NETHER);
        if (!nether && !biome.is(BiomeTags.IS_END)) return;
        // Nether band: hold the explosion until the player is as deep as "Entered the Nether"
        // (reached_nether) — the SAME NetherBand.CORE_DEPTH_BLOCKS gate ZoneProgressEvents uses, so
        // a bed can never detonate before the advancement that warns them fires. Formula-sampled,
        // not a second getBiome() 400 blocks away, so no chunk is forced. Scoped to worlds that
        // actually run the band (overworld + startX != OFF); any other bedWorks dimension carrying
        // a Nether-tagged biome keeps the plain baked-biome behaviour.
        if (nether
            && Level.OVERWORLD.equals(serverLevel.dimension())
            && NetherBand.startX(serverLevel) != NetherBand.OFF
            && !NetherBand.isDeepInNetherCore(serverLevel, pos.getX())) {
            return;
        }

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
