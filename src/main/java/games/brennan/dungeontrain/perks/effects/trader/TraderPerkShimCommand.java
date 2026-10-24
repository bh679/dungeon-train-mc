package games.brennan.dungeontrain.perks.effects.trader;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * TEMPORARY SHIM: {@code /dungeontrain debug perk set <id> <rank>} for the dev client until the
 * perks foundation lands its own. Registered through its own event so {@code DebugCommand} is
 * untouched (Brigadier merges the shared literals). Dev builds only. Delete with
 * {@link Perks}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TraderPerkShimCommand {

    private TraderPerkShimCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        if (!DungeonTrain.isDevBuild()) return;
        event.getDispatcher().register(Commands.literal("dungeontrain")
            .then(Commands.literal("debug")
                .then(Commands.literal("perk")
                    .then(Commands.literal("set")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .then(Commands.argument("rank", IntegerArgumentType.integer(0))
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String id = StringArgumentType.getString(ctx, "id");
                                    int rank = IntegerArgumentType.getInteger(ctx, "rank");
                                    Perks.shimSet(player, id, rank);
                                    ctx.getSource().sendSuccess(
                                        () -> Component.literal("[shim] perk " + id + " rank " + rank), false);
                                    return 1;
                                })))))));
    }
}
