package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import games.brennan.discordpresence.survey.SurveyManager;
import games.brennan.dungeontrain.client.BugLogReporter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * The {@code /bug} command: opens the feedback survey jumped straight to the bug-report question
 * (skipping the emotional feedback questions), for the running player in any game mode. Not
 * op-gated ({@code requires(s -> true)}); player-only, since a screen can only open on a client
 * (the console gets the vanilla "player only" error).
 *
 * <p>Answering the bug question in the opened survey ships the player's logs to the server the
 * same way the death screen does — DP's {@code SurveyScreen} routes the submission through
 * {@code SurveySubmitClientHook} into {@link BugLogReporter}. Whitelisted in
 * {@code CommandAllowlist} so running it never taints a run.</p>
 */
public final class BugCommand {

    private BugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("bug")
                        .requires(source -> true) // everyone, any game mode
                        .executes(BugCommand::run));
    }

    private static int run(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        // BUG_REPORT_ID is a compile-time constant, so this reference is inlined and never
        // class-loads the client-only BugLogReporter on a dedicated server.
        SurveyManager.get().openSurveyFor(player, BugLogReporter.BUG_REPORT_ID);
        return 1;
    }
}
