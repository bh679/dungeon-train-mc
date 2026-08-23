package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import games.brennan.dungeontrain.cheat.ConfigReset;
import games.brennan.dungeontrain.cheat.DtConfigIntegrity;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

/**
 * The {@code /fixconfig} command: the in-world half of the config reset offered on the title
 * screen ({@code ConfigDeviationScreen}), and the one-click "action to fix" behind the DT-config
 * Free Play notice (see {@link DtConfigIntegrity}).
 *
 * <p>Moves every governed config aside — DT's two gameplay tomls and AIS's properties — so each
 * mod writes fresh defaults on next launch, and the player's own files stay recoverable under
 * their {@code .bak-<stamp>} names. It takes no caller input and the worst any player can do with
 * it is restore the intended settings, so it is open to everyone
 * ({@code requires(s -> true)}, mirroring {@code /fixaisconfig} and {@code /bug}). Allowlisted in
 * {@code CommandAllowlist} so running the fix never itself taints a run.</p>
 *
 * <p>The configs are read once at launch, so the session Free Play flag stays set until the game
 * (or dedicated server) restarts — the success message says exactly that.</p>
 */
public final class FixConfigCommand {

    private FixConfigCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("fixconfig")
                        .requires(source -> true) // everyone, any game mode — see class doc
                        .executes(FixConfigCommand::run));
    }

    private static int run(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ConfigReset.Result result = ConfigReset.run(FMLPaths.CONFIGDIR.get());
        if (!result.success()) {
            source.sendFailure(Component.translatable("command.dungeontrain.fix_config.fail",
                String.join(", ", result.failed())));
            return 0;
        }
        if (result.moved().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.dungeontrain.fix_config.nothing")
                .withStyle(ChatFormatting.GREEN), false);
            return 1;
        }
        source.sendSuccess(() -> Component.translatable("command.dungeontrain.fix_config.success")
            .withStyle(ChatFormatting.GREEN), false);
        for (ConfigReset.Moved moved : result.moved()) {
            source.sendSuccess(() -> Component.translatable("command.dungeontrain.fix_config.backup",
                moved.file(), moved.backup()).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }
}
