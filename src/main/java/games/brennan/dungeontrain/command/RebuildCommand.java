package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import games.brennan.dungeontrain.net.BuilderReconcileStartPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /dtrebuild} — re-upload builds the relay has lost.
 *
 * <p>The same work the card offered after joining, for anyone who dismissed it, never saw it, or is
 * putting a second install back together. {@code /dtrebuild backups} also includes builds whose only
 * surviving copy is inside a backup archive — off by default there for the same reason it is off on
 * the card: a build with no file left may have been deleted on purpose.</p>
 *
 * <p>A root command rather than a {@code /dungeontrain} subcommand, because those need permission
 * level 2 and this is for every player — the same call {@code /dtbackup} and {@code /dtrestore} make.
 * It can only add rows to the player's own relay profile, so it is open to everyone.</p>
 */
public final class RebuildCommand {

    private RebuildCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("dtrebuild")
                        .requires(source -> true) // everyone — see class doc
                        .executes(ctx -> run(ctx, false))
                        .then(Commands.literal("backups").executes(ctx -> run(ctx, true))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, boolean includeBackups) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.dungeontrain.rebuild.player_only"));
            return 0;
        }
        MinecraftServer server = player.getServer();
        if (server == null) return 0;
        if (!BuilderRelayUpload.canUpload(player)) {
            // Either the server has builder profiles off or this player hasn't granted network
            // consent. Both mean their builds were never uploaded, so there is nothing to put back.
            source.sendFailure(Component.translatable("command.dungeontrain.rebuild.unavailable"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.dungeontrain.rebuild.checking")
                .withStyle(ChatFormatting.GRAY), false);
        // Everything past here reports in chat as it goes: the scan is a relay round trip and the
        // uploads are deliberately paced, so a command return value could only ever mean "started".
        BuilderReconcileStartPacket.start(player, server.overworld(), includeBackups);
        return 1;
    }
}
