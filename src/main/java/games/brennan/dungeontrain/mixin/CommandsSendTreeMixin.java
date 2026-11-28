package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.command.PerksCommandVisibility;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks the building of a client's command tree; see {@link PerksCommandVisibility}. */
@Mixin(Commands.class)
public abstract class CommandsSendTreeMixin {

    @Inject(method = "sendCommands", at = @At("HEAD"))
    private void dungeontrain$beginTree(ServerPlayer player, CallbackInfo ci) {
        PerksCommandVisibility.beginTree();
    }

    @Inject(method = "sendCommands", at = @At("RETURN"))
    private void dungeontrain$endTree(ServerPlayer player, CallbackInfo ci) {
        PerksCommandVisibility.endTree();
    }
}
