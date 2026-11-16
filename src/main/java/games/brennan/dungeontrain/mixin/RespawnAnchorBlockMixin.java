package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.nap.HostileBandBeds;
import games.brennan.dungeontrain.nap.NapService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Respawn anchors work in the Nether, including DT's Nether band core, and explode everywhere else,
 * the mirror of {@code BedBlockMixin}. Vanilla only asks the dimension ({@code canSetSpawn}), so in
 * the band, which is the overworld dimension, a charged anchor would explode; here it behaves as it
 * would in the real Nether instead. The overworld, the End band and the real End keep vanilla's
 * explosion. "Where" is the anchor's live position ({@link HostileBandBeds}), so a carriage anchor
 * follows the train in and out of the Nether.
 *
 * <p>A natural anchor that works here, in a run that allows it, offers a nap ({@link NapService})
 * instead of setting the spawn point, unless monsters are nearby (vanilla's bed message).</p>
 */
@Mixin(RespawnAnchorBlock.class)
public abstract class RespawnAnchorBlockMixin {

    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$netherAnchor(BlockState state, Level level, BlockPos pos, Player player,
                                           BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (level.isClientSide || state.getValue(RespawnAnchorBlock.CHARGE) == 0) return;
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) return;
        if (!HostileBandBeds.anchorWorks(serverLevel, pos)) return;   // overworld / End: vanilla explodes

        NapService.Outcome nap = NapService.offer(serverPlayer, pos, false);
        if (nap == NapService.Outcome.OFFERED) {
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }
        if (nap == NapService.Outcome.MONSTERS) {
            // No nap with monsters about, the same refusal a bed gives; the anchor still sets spawn.
            serverPlayer.displayClientMessage(Component.translatable("block.minecraft.bed.not_safe"), true);
        }
        if (nap == NapService.Outcome.GUESTS) {
            serverPlayer.displayClientMessage(NapService.tamperedMessage(nap), true);
        }
        if (level.dimension() == Level.NETHER) return;                // the real Nether: vanilla sets spawn

        // The Nether band: do what vanilla does in the Nether rather than exploding.
        if (serverPlayer.getRespawnDimension() != level.dimension() || !pos.equals(serverPlayer.getRespawnPosition())) {
            serverPlayer.setRespawnPosition(level.dimension(), pos, 0.0F, false, true);
            level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                SoundEvents.RESPAWN_ANCHOR_SET_SPAWN, SoundSource.BLOCKS, 1.0F, 1.0F);
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }
        cir.setReturnValue(InteractionResult.CONSUME);
    }
}
