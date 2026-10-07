package games.brennan.dungeontrain.mixin;

import io.github.mortuusars.exposure.network.packet.serverbound.AlbumSignC2SP;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Albums cannot be signed: a player's album always carries their name, and a signed album is what a
 * found (read-only) album is. The client never offers it ({@link AlbumMenuMixin}); this refuses a
 * sign request a modified client sends anyway.
 */
@Mixin(value = AlbumSignC2SP.class, remap = false)
public abstract class AlbumSignC2SPMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, require = 0)
    private void dungeontrain$refuseSigning(PacketFlow flow, Player player, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }
}
