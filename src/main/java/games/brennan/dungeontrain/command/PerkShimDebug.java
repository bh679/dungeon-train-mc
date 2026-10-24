package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/**
 * TEMPORARY SHIM command for the perks foundation: {@code /dungeontrain debug perk set <id> <rank>},
 * {@code ... perk clear} and {@code ... perk list}, writing the static ranks in the shim {@link Perks}. Dev builds
 * only. Mirrors the foundation's command shape so the Gate 2 steps carry over; delete with the shim.
 */
final class PerkShimDebug {

    private PerkShimDebug() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("perk")
            .requires(src -> DungeonTrain.isDevBuild())
            .then(Commands.literal("set")
                .then(Commands.argument("id", StringArgumentType.word())
                    .then(Commands.argument("rank", IntegerArgumentType.integer(0, 100))
                        .executes(ctx -> set(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                IntegerArgumentType.getInteger(ctx, "rank"))))))
            .then(Commands.literal("clear").executes(ctx -> clear(ctx.getSource())))
            .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())));
    }

    private static int set(CommandSourceStack src, String id, int rank) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        Perks.shimSet(p, id, rank);
        src.sendSuccess(() -> Component.literal("[perk shim] " + id + " = " + rank), false);
        return 1;
    }

    private static int clear(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        Perks.shimClear(p);
        src.sendSuccess(() -> Component.literal("[perk shim] all ranks cleared"), false);
        return 1;
    }

    private static int list(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        Map<String, Integer> view = Perks.shimView(p);
        String text = view.isEmpty() ? "[perk shim] no active perks" : "[perk shim] " + view;
        src.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
