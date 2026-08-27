package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.data.PlayerDataRecovery;
import games.brennan.dungeontrain.template.TemplateStores;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.util.List;

/**
 * {@code /dtrestore} — list every place the player's builds and progress could be recovered from,
 * and put one of them back.
 *
 * <p>The in-world half of {@code DataRecoveryScreen}, and the way to pick a candidate other than
 * the top-ranked one the screen offers. {@code /dtrestore} on its own lists what was found, with
 * an index and a full path; {@code /dtrestore <n>} restores that entry.</p>
 *
 * <p>Restoring is additive — a file that already exists on disk is left alone, and a sibling
 * instance is copied from, never moved — so the command cannot make anyone worse off. That is why
 * it is open to everyone, like {@code /dtbackup} and {@code /fixconfig}, and why it is a root
 * command rather than a permission-2 {@code /dungeontrain} subcommand.</p>
 */
public final class RestoreCommand {

    private RestoreCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("dtrestore")
                        .requires(source -> true) // everyone, any game mode — see class doc
                        .executes(RestoreCommand::list)
                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .executes(RestoreCommand::restore)));
    }

    private static List<PlayerDataRecovery.Candidate> candidates() {
        return PlayerDataRecovery.findCandidates(PlayerDataPaths.root(), FMLPaths.GAMEDIR.get());
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<PlayerDataRecovery.Candidate> found = candidates();
        if (found.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.dungeontrain.restore.none")
                .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.dungeontrain.restore.header")
            .withStyle(ChatFormatting.GREEN), false);
        for (int i = 0; i < found.size(); i++) {
            PlayerDataRecovery.Candidate candidate = found.get(i);
            int index = i + 1;
            source.sendSuccess(() -> Component.translatable("command.dungeontrain.restore.entry",
                index, candidate.description(), candidate.path().toString())
                .withStyle(ChatFormatting.GRAY), false);
        }
        return found.size();
    }

    private static int restore(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<PlayerDataRecovery.Candidate> found = candidates();
        int index = IntegerArgumentType.getInteger(ctx, "index");
        if (index > found.size()) {
            source.sendFailure(Component.translatable("command.dungeontrain.restore.no_such", index));
            return 0;
        }
        PlayerDataRecovery.Candidate candidate = found.get(index - 1);
        try {
            int written = PlayerDataRecovery.restore(
                candidate, PlayerDataPaths.root(), PlayerDataPaths.dtpacksRoot());
            // Recovered templates are on disk but not in any cache — without the barrier they
            // wouldn't appear in the editor until the next restart. The importer pass runs too:
            // a backup can carry dtpacks/<name>.zip, which only becomes a package once extracted.
            TemplateStores.reloadAll(true);
            source.sendSuccess(() -> Component.translatable("command.dungeontrain.restore.success",
                written, candidate.path().toString()).withStyle(ChatFormatting.GREEN), false);
            return written;
        } catch (IOException e) {
            source.sendFailure(Component.translatable("command.dungeontrain.restore.fail", e.toString()));
            return 0;
        }
    }
}
