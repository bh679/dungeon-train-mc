package games.brennan.dungeontrain.mixin.vista;

import net.minecraft.server.level.ServerPlayer;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.compat.DisabledModContent;
import games.brennan.dungeontrain.compat.vista.TvBlocks;
import games.brennan.dungeontrain.compat.vista.TvChannelCycle;
import games.brennan.dungeontrain.compat.vista.TvItems;
import games.brennan.dungeontrain.player.ServerLiveStatus;
import games.brennan.dungeontrain.compat.vista.TvPowerToggle;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.mehvahdjukaar.vista.common.tv.TVBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.mehvahdjukaar.vista.common.connection.IConnectedBlock;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.minecraft.world.level.block.Block;

/**
 * Vista TVs as latches: a redstone pulse or a right-click toggles them, a steady signal is not
 * required — see {@link TvPowerToggle}. Vista's own grid propagation, sounds and screen merging are
 * untouched; only the one {@code hasNeighborSignal} read its neighbour handler makes is answered by
 * the latch instead of the wire.
 *
 * <p>Click rules: right-click → cycle power / stream, whatever is in hand (the held item is not used or
 * placed). Sneak + item uses the item as normal (vanilla skips the block then), which is how a block
 * — or another TV — is placed against one. Sneak + empty hand keeps Vista's behaviour (pause while
 * playing, eject the cassette while off) — except that a Live Feed Cassette never ejects.</p>
 *
 * <p>The right-click is a cycle, not a toggle, while the Two Streams reward has a second stream live:
 * off → Stream 1 → Stream 2 → off ({@link TvChannelCycle}; the server's {@link ServerLiveStatus} says
 * whether a second stream is live). With one stream, or none, it is the plain on/off it always was.</p>
 *
 * <p>A TV that comes on with an empty slot tunes itself: the server puts a Live Feed Cassette in, so
 * switching any TV on shows the broadcast (or the replay) with no setup at all. That cassette is never
 * a player's item — see {@code event.UnobtainableLiveItems} and {@link TVBlockEntityMixin}.</p>
 *
 * <p>A hand-held TV that was switched on ({@code compat/vista/TvItems}) is placed switched on, through
 * the same latch, so it comes up tuned like any other TV that comes on.</p>
 *
 * <p>A broken TV drops one TV and nothing else. Vista spills the slot on removal like a chest; in DT the
 * slot only ever holds a cassette ({@link TVBlockEntityMixin}), and no cassette is ever a player's item,
 * so the spill is skipped outright rather than relying on each dropped stack being caught later.</p>
 */
@Mixin(value = TVBlock.class, remap = false)
public abstract class TVBlockMixin {

    @Redirect(method = "neighborChanged",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;hasNeighborSignal(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean dungeontrain$latchedSignal(Level level, BlockPos pos, BlockState state, Level level2, BlockPos pos2) {
        boolean actual = level.hasNeighborSignal(pos);
        boolean currentlyOn = state.getValue(TVBlock.POWER_STATE).isOn();
        return TvPowerToggle.desiredSignal(level, pos, actual, currentlyOn);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$clickToggles(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit,
                                           CallbackInfoReturnable<ItemInteractionResult> cir) {
        if (!player.isSecondaryUseActive()) {
            // Any right-click cycles off → Stream 1 → Stream 2 (while a second stream is live) → off, held
            // item or not; returning success keeps the item from being used.
            if (!level.isClientSide) dungeontrain$cycle(level, pos, state, player);
            cir.setReturnValue(ItemInteractionResult.sidedSuccess(level.isClientSide));
            return;
        }
        if (DisabledModContent.isDisabledItem(stack)) {
            // Vista's own cassettes are hidden content; a TV never takes one, even from /give.
            cir.setReturnValue(ItemInteractionResult.FAIL);
            return;
        }
        if (stack.isEmpty() && !state.getValue(TVBlock.POWER_STATE).isOn()
                && dungeontrain$liveTunedTv(level, pos, state) != null) {
            // Vista would eject the cassette of a TV that is off; the Live Feed one stays put.
            cir.setReturnValue(ItemInteractionResult.sidedSuccess(level.isClientSide));
        }
    }

    /** Server side: one step of the right-click cycle ({@link TvChannelCycle}) for the screen at {@code pos}. */
    private void dungeontrain$cycle(Level level, BlockPos pos, BlockState state, Player player) {
        boolean on = state.getValue(TVBlock.POWER_STATE).isOn();
        TVBlockEntity tv = dungeontrain$liveTunedTv(level, pos, state);
        boolean onSecond = tv != null && TvBlocks.isSecondCassette(tv.getItem(0));
        boolean secondLive = level.getServer() != null && ServerLiveStatus.isSecondStreamLive(level.getServer());
        TvChannelCycle.Step step = TvChannelCycle.next(on, tv != null, onSecond, secondLive);
        ServerPlayer serverPlayer = player instanceof ServerPlayer sp ? sp : null;
        switch (step) {
            case TO_SECOND -> {
                dungeontrain$retune(level, tv, TvBlocks.liveCassette(true));
                level.playSound(null, tv.getBlockPos(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.6f, 1.2f);
                if (serverPlayer != null) {
                    serverPlayer.displayClientMessage(Component.translatable("gui.dungeontrain.tv.channel.second"), true);
                    ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(serverPlayer, "switched_tv_channel");
                }
            }
            case TURN_ON, TURN_OFF -> {
                // Off from Stream 2 goes back to Stream 1, so the next cycle starts where it should.
                if (step == TvChannelCycle.Step.TURN_OFF && onSecond) dungeontrain$retune(level, tv, TvBlocks.liveCassette(false));
                TvPowerToggle.toggle(level, pos);
                if (serverPlayer != null) {
                    if (step == TvChannelCycle.Step.TURN_ON && secondLive) {
                        serverPlayer.displayClientMessage(Component.translatable("gui.dungeontrain.tv.channel.main"), true);
                    }
                    ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(serverPlayer, "toggled_placed_tv");
                }
            }
        }
    }

    /** Put {@code cassette} in the screen's tuner; the slot change syncs to every client like any cassette change. */
    private static void dungeontrain$retune(Level level, TVBlockEntity tv, ItemStack cassette) {
        tv.setItem(0, cassette);
        tv.setChanged();
        level.sendBlockUpdated(tv.getBlockPos(), tv.getBlockState(), tv.getBlockState(), Block.UPDATE_CLIENTS);
    }

    /** The screen's master block entity when it holds either Live Feed Cassette, else null. */
    @Nullable
    private TVBlockEntity dungeontrain$liveTunedTv(Level level, BlockPos pos, BlockState state) {
        return ((IConnectedBlock) (Object) this).findMasterBlockEntity(level, pos, state) instanceof TVBlockEntity tv
            && TvBlocks.isLiveCassette(tv.getItem(0)) ? tv : null;
    }

    @Inject(method = "neighborChanged", at = @At("TAIL"))
    private void dungeontrain$autoTune(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos,
                                       boolean movedByPiston, CallbackInfo ci) {
        if (level.isClientSide) return;
        BlockState now = level.getBlockState(pos);
        if (!now.is(state.getBlock()) || !now.getValue(TVBlock.POWER_STATE).isOn()) return;
        if (((IConnectedBlock) (Object) this).findMasterBlockEntity(level, pos, now) instanceof TVBlockEntity tv
                && tv.getItem(0).isEmpty()) {
            tv.setItem(0, TvBlocks.liveCassette(false));
            tv.setChanged();
            level.sendBlockUpdated(tv.getBlockPos(), tv.getBlockState(), tv.getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Redirect(method = "onRemove",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/world/Containers;dropContentsOnDestroy(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private void dungeontrain$noSpill(BlockState state, BlockState newState, Level level, BlockPos pos) {
        // Deliberately empty: a TV's slot is its tuner, never storage.
    }

    @Inject(method = "setPlacedBy", at = @At("TAIL"))
    private void dungeontrain$placeOn(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack,
                                      CallbackInfo ci) {
        if (TvItems.isOn(stack)) TvPowerToggle.toggle(level, pos);
    }
}
