package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.InstantRespawnReboard;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the local player's death to {@link InstantRespawnReboard} when vanilla's
 * <b>Immediate Respawn</b> game rule ({@code doImmediateRespawn}) is on, so a
 * Dungeon Train run ends in a fresh world instead of respawning in place.
 *
 * <p>With the rule off, {@code handlePlayerCombatKill} opens a {@code DeathScreen}
 * and {@code DeathScreenLayoutHandler} swaps in the narrative recap — untouched
 * here. With it on, vanilla instead calls {@code LocalPlayer.respawn()}, which is
 * the case this mixin cancels. The packet's death message is handed over too:
 * an abandoned run opens the death screen itself and titles it the same way
 * vanilla's rule-off branch would.</p>
 *
 * <p>Injected at {@code HEAD} rather than at the {@code respawn()} call so the
 * injection point cannot drift with the method's internals: the trade-off is that
 * this also runs on the netty-thread pass, before vanilla's
 * {@code PacketUtils.ensureRunningOnSameThread} re-dispatches the packet to the
 * client thread. {@code InstantRespawnReboard} declines that first pass and acts
 * on the client-thread one.</p>
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerInstantRespawnMixin {

    @Inject(method = "handlePlayerCombatKill", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$instantRespawnStartsNewWorld(
            ClientboundPlayerCombatKillPacket packet, CallbackInfo ci
    ) {
        if (InstantRespawnReboard.interceptDeath(packet.playerId(), packet.message())) {
            ci.cancel();
        }
    }
}
