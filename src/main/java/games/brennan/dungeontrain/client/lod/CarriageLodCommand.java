package games.brennan.dungeontrain.client.lod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Client command for the far-carriage render LOD.
 *
 * <ul>
 *   <li>{@code /carriage-lod}                — same as {@code status}</li>
 *   <li>{@code /carriage-lod on|off}         — matched toggle for A/B measurement, and a safety valve</li>
 *   <li>{@code /carriage-lod distance <n>}   — move the near/far threshold live</li>
 *   <li>{@code /carriage-lod status}         — tier split, baked-mesh count, threshold</li>
 * </ul>
 *
 * <p>Registered through {@link RegisterClientCommandsEvent} rather than DT's server-side
 * {@code DebugCommand}, following {@link games.brennan.dungeontrain.client.FramerateThrottleCommand}.
 * The LOD is a purely client-side rendering choice with no server state behind it, so routing it
 * through the server dispatcher would mean inventing a packet to carry a setting the server has no
 * opinion about — and it would stop working on multiplayer servers that do not run DT's command.</p>
 *
 * <p>Toggling off promotes every far carriage back on the next reconcile and frees the baked
 * meshes, so {@code off} is a complete revert rather than just a pause — which is what makes it a
 * usable A/B control and a usable safety valve.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CarriageLodCommand {

    private CarriageLodCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("carriage-lod")
            .executes(ctx -> status(ctx.getSource()))
            .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
            .then(Commands.literal("on").executes(ctx -> setEnabled(ctx.getSource(), true)))
            .then(Commands.literal("off").executes(ctx -> setEnabled(ctx.getSource(), false)))
            .then(Commands.literal("distance")
                .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(
                        CarriageLodController.MIN_FAR_DISTANCE_BLOCKS,
                        CarriageLodController.MAX_FAR_DISTANCE_BLOCKS))
                    .executes(ctx -> setDistance(
                        ctx.getSource(), DoubleArgumentType.getDouble(ctx, "blocks")))));
        dispatcher.register(root);
    }

    private static int setEnabled(CommandSourceStack source, boolean enabled) {
        CarriageLodController.ENABLED = enabled;
        CarriageLodLog.reset();
        if (!enabled) {
            // Revert immediately rather than waiting for the next reconcile, so an A/B measurement
            // never samples a frame that is halfway between the two states.
            CarriageLodController.promoteAll(Minecraft.getInstance().level);
            CarriageMeshCache.clear();
        }
        source.sendSystemMessage(Component.literal(
                "Far-carriage render LOD " + (enabled ? "ENABLED" : "DISABLED"))
            .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        if (enabled) {
            source.sendSystemMessage(Component.literal(
                    "  Carriages beyond " + fmt(CarriageLodController.farDistanceBlocks)
                        + " blocks render as a baked mesh.")
                .withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }

    private static int setDistance(CommandSourceStack source, double blocks) {
        double applied = CarriageLodController.setFarDistance(blocks);
        CarriageLodLog.reset();
        source.sendSystemMessage(Component.literal(
                "Far-carriage LOD threshold set to " + fmt(applied) + " blocks")
            .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int status(CommandSourceStack source) {
        source.sendSystemMessage(Component.literal(
                "Far-carriage render LOD: " + (CarriageLodController.ENABLED ? "ON" : "OFF"))
            .withStyle(CarriageLodController.ENABLED ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        source.sendSystemMessage(Component.literal(String.format(
                "  tracked=%d near=%d far=%d baked=%d threshold=%s blocks",
                CarriageLodController.lastTracked(),
                CarriageLodController.lastNear(),
                CarriageLodController.lastFar(),
                CarriageMeshCache.size(),
                fmt(CarriageLodController.farDistanceBlocks)))
            .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static String fmt(double blocks) {
        return String.format("%.0f", blocks);
    }
}
