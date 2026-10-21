package games.brennan.dungeontrain.mixin.vista;

import games.brennan.dungeontrain.registry.ModItems;
import net.mehvahdjukaar.vista.common.cassette.ITvCassette;
import net.mehvahdjukaar.vista.common.tv.TVBlock;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A TV's one slot is its tuner, never storage.
 *
 * <p>A hopper never pulls the Live Feed Cassette out of a TV: the cassette is how the TV stays tuned, and
 * it is never a player's item (see {@code event.UnobtainableLiveItems}). Vista lets any face take the
 * slot; every other cassette still comes out as Vista ships it.</p>
 *
 * <p>And a TV never loads holding anything but a cassette. TV rooms once had loot pools authored on the
 * TVs themselves, so carriages already in players' saves have TVs hiding an emerald or a sword that
 * spilled when the TV broke, and that never tuned because the slot was taken. On load the stray stack is
 * dropped from the slot (not the world), and a TV that is switched on is tuned in its place. The roller
 * no longer fills a TV ({@code editor.ContainerContentsRoller}); this is the backstop for saves and for
 * any other path in.</p>
 */
@Mixin(value = TVBlockEntity.class, remap = false)
public abstract class TVBlockEntityMixin {

    @Inject(method = "canTakeItemThroughFace", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$keepLiveCassette(int slot, ItemStack stack, Direction side,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (stack.is(ModItems.LIVE_CASSETTE.get())) cir.setReturnValue(false);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void dungeontrain$onlyCassettes(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        TVBlockEntity tv = (TVBlockEntity) (Object) this;
        ItemStack held = tv.getItem(0);
        if (held.isEmpty() || held.getItem() instanceof ITvCassette) return;
        // No setChanged here: level may still be null on chunk load, and the chunk saves this state anyway.
        tv.removeItemNoUpdate(0);
        BlockState state = tv.getBlockState();
        if (state.hasProperty(TVBlock.POWER_STATE) && state.getValue(TVBlock.POWER_STATE).isOn()) {
            tv.setItem(0, new ItemStack(ModItems.LIVE_CASSETTE.get()));
        }
    }
}
