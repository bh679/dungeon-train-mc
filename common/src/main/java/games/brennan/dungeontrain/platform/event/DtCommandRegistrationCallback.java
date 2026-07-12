package games.brennan.dungeontrain.platform.event;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Loader-neutral form of NeoForge's {@code RegisterCommandsEvent}: called once
 * per server start (both integrated and dedicated), on the server thread, while
 * commands are being built. Not cancellable; the dispatcher is mutated by
 * registering command nodes (the same side effect the NeoForge handler had).
 *
 * <p>All three parameters are vanilla Minecraft / Brigadier types, so a Fabric
 * bridge can supply them from its own {@code CommandRegistrationCallback} with
 * no translation.</p>
 */
@FunctionalInterface
public interface DtCommandRegistrationCallback {

    void register(CommandDispatcher<CommandSourceStack> dispatcher,
                  CommandBuildContext buildContext,
                  Commands.CommandSelection selection);
}
