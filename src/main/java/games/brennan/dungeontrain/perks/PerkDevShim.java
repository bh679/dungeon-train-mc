package games.brennan.dungeontrain.perks;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * SHIM: delete when the perks foundation merges to main (it ships the real
 * {@code /dungeontrain debug perk set <id> <rank>}).
 *
 * <p>Sets an in-memory perk rank on the calling player so perk effects can be exercised in a
 * dev client. Dev builds only.</p>
 */
public final class PerkDevShim {

    private PerkDevShim() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("perk")
            .requires(src -> DungeonTrain.isDevBuild() && src.hasPermission(2))
            .then(Commands.literal("set")
                .then(Commands.argument("id", StringArgumentType.word())
                    .then(Commands.argument("rank", IntegerArgumentType.integer(0))
                        .executes(ctx -> set(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id"),
                            IntegerArgumentType.getInteger(ctx, "rank"))))));
    }

    private static int set(CommandSourceStack source, String id, int rank) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("[perk] run this as a player"));
            return 0;
        }
        Perks.set(player, id, rank);
        source.sendSuccess(() -> Component.literal("[perk] " + id + " rank " + rank + " (shim, in memory)"), false);
        return 1;
    }
}
