package games.brennan.dungeontrain.client.constellation;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Dev builds only: {@code /dungeontrain-debug-constellation-edge <soft|hard>} picks how the
 * constellation patch's rim meets the sky ({@link ConstellationEdge}), to try them in-game.
 * Client-side, because the patch is: it works on any server, and changes nothing but this client's
 * drawing. Not persisted; a restart is back on {@link ConstellationEdge#SOFT}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ConstellationEdgeCommand {

    public static final String ROOT = "dungeontrain-debug-constellation-edge";

    private ConstellationEdgeCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (!DungeonTrain.isDevBuild()) return;
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT)
            .executes(ctx -> report(ctx.getSource()));
        for (ConstellationEdge edge : ConstellationEdge.values()) {
            String name = edge.name().toLowerCase(java.util.Locale.ROOT);
            root.then(Commands.literal(name).executes(ctx -> set(ctx.getSource(), edge)));
        }
        event.getDispatcher().register(root);
    }

    private static int set(CommandSourceStack source, ConstellationEdge edge) {
        ConstellationEdge.set(edge);
        return report(source);
    }

    private static int report(CommandSourceStack source) {
        ConstellationEdge edge = ConstellationEdge.current();
        source.sendSystemMessage(Component.literal("Constellation edge: " + edge.name().toLowerCase(java.util.Locale.ROOT)
            + " (the rim against the sky)")
            .withStyle(ChatFormatting.AQUA));
        return 1;
    }
}
