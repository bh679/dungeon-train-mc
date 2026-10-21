package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.HiddenCommands;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prunes DT's hidden commands from the suggestion tree the server just sent (merged with NeoForge's
 * client commands by then), so survival tab completion offers {@code /bug} and nothing else of DT's —
 * see {@link HiddenCommands}. Dev builds keep the full tree for the tooling.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerCommandsMixin {

    @Inject(method = "handleCommands", at = @At("TAIL"))
    private void dungeontrain$hideCommands(ClientboundCommandsPacket packet, CallbackInfo ci) {
        if (DungeonTrain.isDevBuild()) return;
        HiddenCommands.prune(((ClientPacketListener) (Object) this).getCommands().getRoot());
    }
}
