package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.event.DifficultyChangeConfirmEvents;
import net.minecraft.network.protocol.game.ServerboundChangeDifficultyPacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the vanilla Options-screen Difficulty button behind Dungeon Train's confirmation — see
 * {@link DifficultyChangeConfirmEvents} for why a difficulty change is worth warning about.
 *
 * <p>Target: {@code ServerGamePacketListenerImpl#handleChangeDifficulty(ServerboundChangeDifficultyPacket)}
 * (1.21.1 Mojmap), which is where every client-driven difficulty change lands — the vanilla button,
 * and any other mod's UI that sends the same packet. Injecting at HEAD keeps the one prompt path
 * server-side instead of duplicating it per screen.</p>
 *
 * <p>Vanilla's own permission test is repeated here (via the player rather than the listener's
 * {@code isSingleplayerOwner}) so a player who couldn't have changed the difficulty anyway is never
 * prompted about it; when it fails, or when the change isn't worth holding, this returns without
 * cancelling and vanilla proceeds untouched.</p>
 */
@Mixin(net.minecraft.server.network.ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplDifficultyMixin {

    @Shadow public ServerPlayer player;

    @Inject(method = "handleChangeDifficulty", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$confirmDifficultyChange(ServerboundChangeDifficultyPacket packet, CallbackInfo ci) {
        if (this.player == null || this.player.getServer() == null) {
            return;
        }
        boolean allowed = this.player.hasPermissions(2)
            || this.player.getServer().isSingleplayerOwner(this.player.getGameProfile());
        if (!allowed) {
            return; // vanilla ignores it anyway — nothing to warn about
        }
        // force=false, announce=false: mirror what vanilla's handler would have done.
        if (DifficultyChangeConfirmEvents.request(this.player, packet.getDifficulty(), false, false)) {
            ci.cancel();
        }
    }
}
