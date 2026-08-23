package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import games.brennan.dungeontrain.cheat.EditorContentIntegrity;
import games.brennan.dungeontrain.world.CustomContentChoice;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /customcontent <on|off|status>} — turn custom Train Editor content on
 * or off for this world, and report what is installed.
 *
 * <p>This is the reversible half of the join-time prompt: the chat notices link
 * straight here, and a player who picked "Disable" (or ticked Remember and later
 * changed their mind) can flip back without editing files.</p>
 *
 * <p><b>A root command with {@code requires(s -> true)}, not a {@code /dt}
 * subcommand</b> — deliberately, and for the same reason {@code /fixaisconfig}
 * is one. The {@code /dungeontrain} root is gated at permission level 2, so in
 * an ordinary Survival world without cheats the whole tree is invisible and a
 * clicked link dead-ends in "Unknown or incomplete command". The player this
 * command exists for is precisely that player. It is also consistent with the
 * prompt itself, which any player may answer.</p>
 *
 * <p><b>Must stay in {@code CommandAllowlist.ALLOWED_ROOTS}.</b> This is what the
 * "turn it off" link runs — a player clicking it to <em>leave</em> Free Play must
 * not be permanently marked for doing so.</p>
 */
public final class CustomContentCommand {

    private CustomContentCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("customcontent")
            .requires(source -> true) // everyone, any game mode — see class doc
            .then(Commands.literal("status")
                .executes(ctx -> runStatus(ctx.getSource())))
            .then(Commands.literal("on")
                .executes(ctx -> runSet(ctx.getSource(), CustomContentChoice.ALLOW)))
            .then(Commands.literal("off")
                .executes(ctx -> runSet(ctx.getSource(), CustomContentChoice.DISABLE)))
            .executes(ctx -> runStatus(ctx.getSource())));
    }

    private static int runStatus(CommandSourceStack source) {
        if (!EditorContentIntegrity.hasCustomContent()) {
            source.sendSuccess(() -> Component.translatable("commands.dungeontrain.customcontent.none")
                .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        String packages = String.join(", ", EditorContentIntegrity.contentPackageNames());
        boolean suppressed = EditorContentIntegrity.isSuppressed();
        source.sendSuccess(() -> Component.translatable(
                suppressed
                    ? "commands.dungeontrain.customcontent.status.off"
                    : "commands.dungeontrain.customcontent.status.on",
                packages)
            .withStyle(suppressed ? ChatFormatting.GRAY : ChatFormatting.AQUA), false);
        return 1;
    }

    private static int runSet(CommandSourceStack source, CustomContentChoice choice) {
        if (!EditorContentIntegrity.hasCustomContent()) {
            source.sendFailure(Component.translatable("commands.dungeontrain.customcontent.none"));
            return 0;
        }
        boolean flipped = EditorContentIntegrity.setWorldChoice(source.getServer(), choice);
        boolean suppressed = choice.suppressesContent();
        if (!flipped) {
            // The choice is recorded either way (UNSET -> ALLOW records an answer without changing
            // what loads), but nothing needs re-stamping, so say so rather than implying a reload.
            source.sendSuccess(() -> Component.translatable(
                    suppressed
                        ? "commands.dungeontrain.customcontent.already_off"
                        : "commands.dungeontrain.customcontent.already_on")
                .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        source.sendSuccess(() -> Component.translatable(
                suppressed
                    ? "commands.dungeontrain.customcontent.now_off"
                    : "commands.dungeontrain.customcontent.now_on")
            .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }
}
