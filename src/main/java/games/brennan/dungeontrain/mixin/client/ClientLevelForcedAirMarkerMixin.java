package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shows Forced Air's bubble marker on nearby Forced Air blocks while a creative player holds it,
 * the way vanilla marks barriers and light blocks. Vanilla's marker list is a fixed set of two
 * items; this answers for a third. The invisible block is otherwise impossible to find again.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelForcedAirMarkerMixin {

    @Inject(method = "getMarkerParticleTarget", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$forcedAirMarker(CallbackInfoReturnable<Block> cir) {
        if (cir.getReturnValue() != null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;
        if (mc.gameMode.getPlayerMode() != GameType.CREATIVE) return;
        if (mc.player.getMainHandItem().is(ModBlocks.FORCED_AIR_ITEM.get())) {
            cir.setReturnValue(ModBlocks.FORCED_AIR.get());
        }
    }
}
