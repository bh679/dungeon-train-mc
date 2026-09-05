package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.data.PlayerDataBackup;
import games.brennan.dungeontrain.data.PlayerDataBackupHook;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.IOException;

/**
 * {@code /dtbackup} — write a restore point of the player's builds and progress right now.
 *
 * <p>Dungeon Train takes one automatically each launch ({@link PlayerDataBackupHook}); this is for
 * the moment before you do something you might regret, and for anyone who wants to see where the
 * archives live. Read-only with respect to the player's data — it only ever adds a zip.</p>
 *
 * <p>A root command rather than a {@code /dungeontrain} subcommand, because those need permission
 * level 2 and this is for every player. Takes no input and can't destroy anything, so it is open
 * to everyone, mirroring {@code /fixconfig}.</p>
 */
public final class BackupCommand {

    private BackupCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("dtbackup")
                        .requires(source -> true) // everyone, any game mode — see class doc
                        .executes(BackupCommand::run));
    }

    private static int run(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            PlayerDataBackup.Result result = PlayerDataBackup.create(
                PlayerDataPaths.backupsRoot(), PlayerDataBackupHook.sources(),
                "manual", VersionInfo.VERSION);
            if (!result.wrote()) {
                source.sendSuccess(() -> Component.translatable("command.dungeontrain.backup.unchanged")
                    .withStyle(ChatFormatting.GREEN), false);
                return 1;
            }
            String name = result.archive().orElseThrow().getFileName().toString();
            source.sendSuccess(() -> Component.translatable("command.dungeontrain.backup.success",
                name, result.fileCount()).withStyle(ChatFormatting.GREEN), false);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.translatable("command.dungeontrain.backup.fail", e.toString()));
            return 0;
        }
    }
}
