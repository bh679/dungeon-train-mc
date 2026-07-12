package games.brennan.dungeontrain.fabric.mixin;

import com.mojang.brigadier.ParseResults;
import games.brennan.dungeontrain.platform.event.DtCommandCallback;
import games.brennan.dungeontrain.platform.event.DtEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gap-filler for {@code DtEvents.COMMAND_EXEC} (NeoForge {@code CommandEvent}, cancellable).
 * Fabric has no command-exec event; fires at the HEAD of {@code Commands.performCommand}
 * and, on the first handler that returns {@code true}, cancels the command (return 0) —
 * matching the NeoForge bridge (DT uses this to block cheat-tainting commands in Free Play).
 */
@Mixin(Commands.class)
public abstract class CommandsPerformMixin {

    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$performCommand(ParseResults<CommandSourceStack> parseResults, String command,
                                             CallbackInfo ci) {
        for (DtCommandCallback cb : DtEvents.COMMAND_EXEC.listeners()) {
            if (cb.onCommand(parseResults)) {
                ci.cancel();
                return;
            }
        }
    }
}
