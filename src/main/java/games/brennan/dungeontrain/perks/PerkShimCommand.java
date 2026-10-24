package games.brennan.dungeontrain.perks;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * TEMPORARY SHIM: {@code /dungeontrain debug perk set <id> <rank>} and {@code perk clear}, dev builds
 * only. Replaced by the foundation's own command when that PR lands.
 */
public final class PerkShimCommand {

    private PerkShimCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("perk")
            .requires(src -> DungeonTrain.isDevBuild())
            .then(Commands.literal("set")
                .then(Commands.argument("id", StringArgumentType.word())
                    .then(Commands.argument("rank", IntegerArgumentType.integer(0))
                        .executes(ctx -> set(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id"),
                            IntegerArgumentType.getInteger(ctx, "rank"))))))
            .then(Commands.literal("clear").executes(ctx -> clear(ctx.getSource())));
    }

    private static int set(CommandSourceStack src, String id, int rank) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        Perks.set(player, id, rank);
        src.sendSuccess(() -> Component.literal("[shim] perk " + id + " rank " + rank), false);
        return rank;
    }

    private static int clear(CommandSourceStack src) throws CommandSyntaxException {
        Perks.clear(src.getPlayerOrException());
        src.sendSuccess(() -> Component.literal("[shim] perks cleared"), false);
        return 1;
    }
}
