package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * TEMPORARY SHIM for {@code /dungeontrain debug perk set <id> <rank>} until the perks foundation's own
 * command lands. Dev builds only. Deleted with {@link Perks}'s shim on rebase.
 */
public final class PerkDebugShimCommand {

    private PerkDebugShimCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("perk")
            .requires(src -> DungeonTrain.isDevBuild())
            .then(Commands.literal("set")
                .then(Commands.argument("id", StringArgumentType.word())
                    .then(Commands.argument("rank", IntegerArgumentType.integer(0, 100))
                        .executes(ctx -> set(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
                            IntegerArgumentType.getInteger(ctx, "rank"))))));
    }

    private static int set(CommandSourceStack src, String id, int rank) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("Run as a player."));
            return 0;
        }
        Perks.setRank(player.getUUID(), id, rank);
        src.sendSuccess(() -> Component.literal("[perk shim] " + id + " = " + rank), false);
        return 1;
    }
}
