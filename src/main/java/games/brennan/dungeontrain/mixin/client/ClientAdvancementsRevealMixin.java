package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.AdvancementRevealClient;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands each applied advancement update to {@link AdvancementRevealClient}, which announces the tabs
 * and advancements an earn just revealed. Runs at the tail so the packet's nodes are already in the tree.
 */
@Mixin(ClientAdvancements.class)
public abstract class ClientAdvancementsRevealMixin {

    @Inject(method = "update", at = @At("TAIL"))
    private void dungeontrain$announceReveals(ClientboundUpdateAdvancementsPacket packet, CallbackInfo ci) {
        AdvancementRevealClient.onUpdate((ClientAdvancements) (Object) this, packet);
    }
}
