package games.brennan.dungeontrain.platform.event;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;

/**
 * Loader-neutral form of NeoForge's {@code RegisterClientCommandsEvent}: called on
 * the client thread while the client-command tree is built (client-side commands
 * run locally, never reach the server). Not cancellable; the dispatcher is mutated
 * by registering command nodes — the same side effect the NeoForge handler had.
 *
 * <p>Both parameters are vanilla Minecraft / Brigadier types, so a Fabric bridge
 * can supply them from {@code ClientCommandRegistrationCallback} with no
 * translation. Distinct from {@link DtCommandRegistrationCallback} (server
 * commands): no {@code CommandSelection}, and it fires on the client.</p>
 */
@FunctionalInterface
public interface DtClientCommandRegistrationCallback {

    void register(CommandDispatcher<CommandSourceStack> dispatcher,
                  CommandBuildContext buildContext);
}
