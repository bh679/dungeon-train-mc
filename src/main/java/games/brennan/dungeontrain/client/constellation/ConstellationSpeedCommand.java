package games.brennan.dungeontrain.client.constellation;

import com.mojang.brigadier.arguments.DoubleArgumentType;
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
 * Dev builds only: {@code /dungeontrain-debug-constellation-speed <x>} sets how fast the constellation
 * patch's arms and edge waves change (1 is the designed pace, 0 freezes them); with no number it says
 * the current speed. Client-side, this client's drawing only. Not persisted; a restart is back on 1.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ConstellationSpeedCommand {

    public static final String ROOT = "dungeontrain-debug-constellation-speed";

    private ConstellationSpeedCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (!DungeonTrain.isDevBuild()) return;
        event.getDispatcher().register(Commands.literal(ROOT)
            .executes(ctx -> report(ctx.getSource()))
            .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.0, 100.0))
                .executes(ctx -> {
                    ConstellationCrumble.setSpeed(DoubleArgumentType.getDouble(ctx, "speed"));
                    return report(ctx.getSource());
                })));
    }

    private static int report(CommandSourceStack source) {
        source.sendSystemMessage(Component.literal("Constellation speed: " + ConstellationCrumble.speed() + "x")
            .withStyle(ChatFormatting.AQUA));
        return 1;
    }
}
