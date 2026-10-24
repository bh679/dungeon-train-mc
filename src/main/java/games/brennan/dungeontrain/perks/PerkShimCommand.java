package games.brennan.dungeontrain.perks;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * TEMPORARY SHIM: {@code /dungeontrain debug perk set <id> <rank>} and {@code perk get <id>} backed by the
 * static map in {@link Perks}. The perks foundation ships the real command under the same path; drop this
 * class (and the one-line hook in {@code DebugCommand}) when rebasing onto it.
 */
public final class PerkShimCommand {

    private PerkShimCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("perk")
            .then(Commands.literal("set")
                .then(Commands.argument("id", StringArgumentType.word())
                    .then(Commands.argument("rank", IntegerArgumentType.integer(0, 1000))
                        .executes(ctx -> set(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
                            IntegerArgumentType.getInteger(ctx, "rank"))))))
            .then(Commands.literal("get")
                .then(Commands.argument("id", StringArgumentType.word())
                    .executes(ctx -> get(ctx.getSource(), StringArgumentType.getString(ctx, "id")))));
    }

    private static int set(CommandSourceStack source, String id, int rank) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[perk] player only"));
            return 0;
        }
        Perks.setRank(player, id, rank);
        source.sendSuccess(() -> Component.literal("[perk] " + id + " rank " + rank + " (shim)"), false);
        return 1;
    }

    private static int get(CommandSourceStack source, String id) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[perk] player only"));
            return 0;
        }
        int rank = Perks.rank(player, id);
        source.sendSuccess(() -> Component.literal("[perk] " + id + " rank " + rank + " (shim)"), false);
        return 1;
    }
}
