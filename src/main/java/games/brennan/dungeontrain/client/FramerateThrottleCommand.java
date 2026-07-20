package games.brennan.dungeontrain.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
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
 * Client command for the idle framerate throttle — see {@link FramerateThrottle}.
 *
 * <ul>
 *   <li>{@code /framerate-throttle}            — same as {@code status}</li>
 *   <li>{@code /framerate-throttle on|off}     — enable / disable, persisted to the client config</li>
 *   <li>{@code /framerate-throttle fps <n>}    — set the cap</li>
 *   <li>{@code /framerate-throttle status}     — report the current state and what it resolves to</li>
 * </ul>
 *
 * <p>Registered through {@link RegisterClientCommandsEvent} rather than the server dispatcher (the
 * pattern {@link NewWorldCommand} uses) because this is a per-player display preference: it must
 * work on multiplayer servers, where the unfocused-window branch is the only one that can fire —
 * {@code Minecraft#isPaused()} is singleplayer-only.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class FramerateThrottleCommand {

    private FramerateThrottleCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("framerate-throttle")
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("on").executes(ctx -> setEnabled(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> setEnabled(ctx.getSource(), false)))
                .then(Commands.literal("fps")
                        .then(Commands.argument("value", IntegerArgumentType.integer(
                                        FramerateThrottle.MIN_THROTTLE_FPS, FramerateThrottle.MAX_THROTTLE_FPS))
                                .executes(ctx -> setFps(
                                        ctx.getSource(), IntegerArgumentType.getInteger(ctx, "value")))));
        dispatcher.register(root);
    }

    private static int setEnabled(CommandSourceStack source, boolean enabled) {
        if (!ClientDisplayConfig.isLoaded()) {
            source.sendFailure(Component.literal("Client config isn't loaded yet — try again in a moment."));
            return 0;
        }
        ClientDisplayConfig.setFramerateThrottleEnabled(enabled);
        source.sendSystemMessage(Component.literal(
                        "Idle framerate throttle " + (enabled ? "ENABLED" : "DISABLED"))
                .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        if (enabled) {
            source.sendSystemMessage(Component.literal(
                            "  Capping to " + ClientDisplayConfig.getFramerateThrottleFps()
                                    + " fps while paused, unfocused, or minimised.")
                    .withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }

    private static int setFps(CommandSourceStack source, int fps) {
        if (!ClientDisplayConfig.isLoaded()) {
            source.sendFailure(Component.literal("Client config isn't loaded yet — try again in a moment."));
            return 0;
        }
        ClientDisplayConfig.setFramerateThrottleFps(fps);
        source.sendSystemMessage(Component.literal("Idle framerate cap set to " + fps + " fps.")
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int status(CommandSourceStack source) {
        Minecraft mc = Minecraft.getInstance();
        boolean enabled = ClientDisplayConfig.isFramerateThrottleEnabled();
        boolean vr = VrCompat.isVivecraftPresent();
        int cap = ClientDisplayConfig.getFramerateThrottleFps();
        int vanillaLimit = mc.getWindow().getFramerateLimit();
        boolean throttlingNow = FramerateThrottle.shouldThrottle(
                mc.isPaused(), mc.isWindowActive(), enabled, vr);

        source.sendSystemMessage(Component.literal("Idle framerate throttle")
                .withStyle(ChatFormatting.AQUA));
        source.sendSystemMessage(Component.literal("  enabled: " + enabled + "   cap: " + cap + " fps")
                .withStyle(ChatFormatting.GRAY));
        source.sendSystemMessage(Component.literal(
                        "  your Max Framerate: " + (vanillaLimit >= 260 ? "Unlimited" : vanillaLimit))
                .withStyle(ChatFormatting.GRAY));
        // Always false when typed in-game — the chat screen is focused and doesn't pause. Shown so
        // the reported state is unambiguous rather than implied.
        source.sendSystemMessage(Component.literal(
                        "  paused: " + mc.isPaused() + "   window focused: " + mc.isWindowActive()
                                + "   throttling right now: " + throttlingNow)
                .withStyle(ChatFormatting.GRAY));
        if (vr) {
            source.sendSystemMessage(Component.literal(
                            "  Vivecraft detected — throttle force-disabled (capping a headset causes motion sickness).")
                    .withStyle(ChatFormatting.YELLOW));
        }
        return 1;
    }
}
