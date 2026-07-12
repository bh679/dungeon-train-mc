package games.brennan.dungeontrain.event;

import com.mojang.brigadier.CommandDispatcher;
import games.brennan.dungeontrain.command.BugCommand;
import games.brennan.dungeontrain.command.DtpCommand;
import games.brennan.dungeontrain.command.EchoEncounterTestCommand;
import games.brennan.dungeontrain.command.TrainCommand;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.ModList;

/**
 * Command registration handler. Converted off NeoForge's event bus (Stage 2a);
 * now a plain {@link games.brennan.dungeontrain.platform.event.DtCommandRegistrationCallback}
 * registered via {@code DtEvents.COMMAND_REGISTRATION} (see
 * {@code NeoForgeServerEvents}), fired by {@code NeoForgeCommandBridge}. Logic is
 * unchanged from the former {@code @SubscribeEvent onRegisterCommands}.
 */
public final class CommandEvents {

    private CommandEvents() {}

    public static void onRegisterCommands(CommandDispatcher<CommandSourceStack> dispatcher,
                                          CommandBuildContext buildContext,
                                          Commands.CommandSelection selection) {
        TrainCommand.register(dispatcher, buildContext);
        // /bug — opens the feedback survey jumped to the bug-report question (logs ship on a
        // real-bug answer, same as the death screen). Bundled DP is always present.
        BugCommand.register(dispatcher);
        // /dtp <x> — teleport to world-X and guarantee a train is there to land on.
        DtpCommand.register(dispatcher);
        // Dev-only: a relay-free way to drive the remote-echo encounter journal end-to-end.
        // Never registered in production, and only when PlayerMob (whose types the command
        // references) is present.
        if (!FMLEnvironment.production && ModList.get().isLoaded("playermob")) {
            EchoEncounterTestCommand.register(dispatcher);
        }
    }
}
