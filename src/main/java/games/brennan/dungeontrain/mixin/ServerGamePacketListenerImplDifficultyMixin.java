package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.event.CheatDetectionEvents;
import net.minecraft.network.protocol.game.ServerboundChangeDifficultyPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Free Play gate for the <b>pause-menu difficulty slider</b>. Dropping the world
 * below {@code NORMAL} takes the run outside the balance Dungeon Train is tuned
 * for, but the slider sends a packet rather than running {@code /difficulty}, so
 * {@code CommandAllowlist} never sees it.
 *
 * <p>Target: {@code ServerGamePacketListenerImpl#handleChangeDifficulty(ServerboundChangeDifficultyPacket)}
 * (1.21.1 Mojmap). The injection point is the {@code MinecraftServer.setDifficulty}
 * call rather than HEAD, deliberately: by then vanilla's
 * {@code PacketUtils.ensureRunningOnSameThread} and permission check have run, so
 * we're on the server thread and only gating a change that would actually have
 * applied.</p>
 *
 * <p>When {@link CheatDetectionEvents#holdDifficultyChange} holds the change we
 * cancel here; the player gets the "Enter Free Play?" prompt and the change is
 * re-applied server-side on confirm, or dropped on cancel.</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplDifficultyMixin {

    @Shadow public ServerPlayer player;

    @Inject(
        method = "handleChangeDifficulty",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/server/MinecraftServer;setDifficulty(Lnet/minecraft/world/Difficulty;Z)V"),
        cancellable = true)
    private void dungeontrain$gateFreePlayDifficulty(ServerboundChangeDifficultyPacket packet, CallbackInfo ci) {
        if (CheatDetectionEvents.holdDifficultyChange(this.player, packet.getDifficulty())) {
            ci.cancel();
        }
    }
}
