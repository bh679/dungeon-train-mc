package games.brennan.dungeontrain.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.slf4j.Logger;

/**
 * Dev-only client command for fast world iteration during testing.
 *
 * <p>Re-uses {@link DeathScreenLayoutHandler#launchWorld(net.minecraft.client.gui.screens.Screen, boolean)}
 * so the world-switch flow is identical to clicking New World / Same World
 * on the death screen — including the Sable sub-level pre-drain.</p>
 *
 * <ul>
 *   <li>{@code /new-world}        — fresh seed, current preset</li>
 *   <li>{@code /new-world fresh}  — same as above (explicit)</li>
 *   <li>{@code /new-world same}   — same seed, re-roll the same world</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class NewWorldCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private NewWorldCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        register(event.getDispatcher(), event.getBuildContext());
    }

    private static void register(
        CommandDispatcher<CommandSourceStack> dispatcher,
        CommandBuildContext buildContext
    ) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("new-world")
            .executes(ctx -> launch(ctx.getSource(), false))
            .then(Commands.literal("fresh").executes(ctx -> launch(ctx.getSource(), false)))
            .then(Commands.literal("same").executes(ctx -> launch(ctx.getSource(), true)));
        dispatcher.register(root);
    }

    private static int launch(CommandSourceStack source, boolean sameSeed) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null) {
            source.sendFailure(Component.literal("/new-world only works in singleplayer."));
            return 0;
        }
        source.sendSystemMessage(Component.literal(
            sameSeed ? "Re-rolling same seed..." : "Rolling new seed..."));
        LOGGER.info("NewWorldCommand: launching {} world via /new-world",
            sameSeed ? "same-seed" : "fresh-seed");
        // launchWorld disconnects + creates the new world. Run from the
        // current screen so the disconnect chain has a context to return to.
        DeathScreenLayoutHandler.launchWorld(
            mc.screen != null ? mc.screen : new TitleScreen(), sameSeed);
        return 1;
    }
}
