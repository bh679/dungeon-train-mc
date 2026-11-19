package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.portal.PortalTwinChunkHold;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player inside a dimensional carriage adds no view-distance tickets, the way a spectator with
 * {@code spectatorsGenerateChunks} off adds none. {@code skipPlayer} is the one question vanilla asks
 * for that, and {@code ChunkMap#move} swaps the player's tickets out and back in whenever its answer
 * flips, so the player still receives every chunk that is loaded but no longer generates the
 * overworld above the room. {@link PortalTwinChunkHold} holds the room's own chunks in their place,
 * and only answers yes once it has.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapTwinSpaceSkipMixin {

    @Inject(method = "skipPlayer", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$skipTwinSpacePlayer(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && PortalTwinChunkHold.isHeld(player)) cir.setReturnValue(true);
    }
}
