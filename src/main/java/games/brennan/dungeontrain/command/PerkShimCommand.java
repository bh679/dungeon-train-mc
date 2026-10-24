package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.PerkDevShim;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * SHIM: {@code /dungeontrain debug perk set <id> <rank>}, dev builds only. The perks foundation
 * ships the real command with the same shape; this file is deleted when it lands.
 */
public final class PerkShimCommand {

    private PerkShimCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("perk")
            .requires(src -> DungeonTrain.isDevBuild())
            .then(Commands.literal("set")
                .then(Commands.argument("id", StringArgumentType.word())
                    .then(Commands.argument("rank", IntegerArgumentType.integer(0, 100))
                        .executes(ctx -> set(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id"),
                            IntegerArgumentType.getInteger(ctx, "rank"))))));
    }

    private static int set(CommandSourceStack source, String id, int rank) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PerkDevShim.set(player, id, rank);
        source.sendSuccess(() -> Component.literal("[perk shim] " + id + " = " + rank), false);
        return rank;
    }
}
