package games.brennan.dungeontrain.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Dev-only client command for fast world iteration during testing.
 *
 * <p>Re-uses {@link DeathScreenLayoutHandler#launchWorld(net.minecraft.client.gui.screens.Screen, boolean)}
 * so the world-switch flow is identical to clicking New World / Same World
 * on the death screen — including the Sable sub-level pre-drain.</p>
 *
 * <ul>
 *   <li>{@code /new-world}                                    — fresh seed, current preset</li>
 *   <li>{@code /new-world fresh}                              — same as above (explicit)</li>
 *   <li>{@code /new-world same}                               — same seed, re-roll the same world</li>
 *   <li>{@code /new-world loop <seconds> <times>}             — every {@code seconds},
 *       roll a fresh-seed new world; repeat {@code times} iterations total. Unattended
 *       harness for stress-testing the save-and-quit pipeline.</li>
 *   <li>{@code /new-world loop <seconds> <times> fresh|same}  — same as above but pin
 *       the seed mode for every iteration.</li>
 *   <li>{@code /new-world loop stop}                          — cancel an active loop.</li>
 * </ul>
 *
 * <p>The loop uses wall-clock pacing driven by {@link ClientTickEvent.Post}. State is
 * static — singleplayer-only by gate, but the static fields survive across the
 * integrated-server restart that {@code launchWorld} triggers, so the loop continues
 * uninterrupted across world reloads. Fires are suppressed while the singleplayer
 * server is offline (title screen, world load), so the interval is effectively
 * "X seconds of in-world wall-clock since the last fire was scheduled."
 */
public final class NewWorldCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Loop state. Volatile so the command thread's writes are visible to the
    // client tick thread; no compound updates so coarse volatility is enough.
    private static volatile int loopRemaining = 0;
    private static volatile long loopIntervalMillis = 0L;
    private static volatile long loopNextFireMillis = 0L;
    private static volatile boolean loopSameSeed = false;

    private NewWorldCommand() {}

    public static void onRegisterClientCommands(CommandDispatcher<CommandSourceStack> dispatcher,
                                                CommandBuildContext buildContext) {
        register(dispatcher, buildContext);
    }

    private static void register(
        CommandDispatcher<CommandSourceStack> dispatcher,
        CommandBuildContext buildContext
    ) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("new-world")
            .executes(ctx -> launch(ctx.getSource(), false))
            .then(Commands.literal("fresh").executes(ctx -> launch(ctx.getSource(), false)))
            .then(Commands.literal("same").executes(ctx -> launch(ctx.getSource(), true)))
            .then(Commands.literal("loop")
                .then(Commands.literal("stop").executes(ctx -> stopLoop(ctx.getSource())))
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                    .then(Commands.argument("times", IntegerArgumentType.integer(1))
                        .executes(ctx -> startLoop(
                            ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "seconds"),
                            IntegerArgumentType.getInteger(ctx, "times"),
                            false))
                        .then(Commands.literal("fresh").executes(ctx -> startLoop(
                            ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "seconds"),
                            IntegerArgumentType.getInteger(ctx, "times"),
                            false)))
                        .then(Commands.literal("same").executes(ctx -> startLoop(
                            ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "seconds"),
                            IntegerArgumentType.getInteger(ctx, "times"),
                            true))))));
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
        DeathScreenLayoutHandler.launchWorld(
            mc.screen != null ? mc.screen : new TitleScreen(), sameSeed);
        return 1;
    }

    private static int startLoop(CommandSourceStack source, int seconds, int times, boolean sameSeed) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null) {
            source.sendFailure(Component.literal("/new-world loop only works in singleplayer."));
            return 0;
        }
        loopIntervalMillis = (long) seconds * 1000L;
        loopRemaining = times;
        loopSameSeed = sameSeed;
        loopNextFireMillis = System.currentTimeMillis() + loopIntervalMillis;
        String seedLabel = sameSeed ? "same-seed" : "fresh-seed";
        source.sendSystemMessage(Component.literal(
            "[new-world loop] armed: " + times + "× " + seedLabel + ", " + seconds + "s interval"));
        LOGGER.info("NewWorldCommand: loop armed times={} seconds={} sameSeed={}",
            times, seconds, sameSeed);
        return 1;
    }

    private static int stopLoop(CommandSourceStack source) {
        int prev = loopRemaining;
        loopRemaining = 0;
        loopNextFireMillis = 0L;
        loopIntervalMillis = 0L;
        source.sendSystemMessage(Component.literal(
            "[new-world loop] stopped (" + prev + " iterations were pending)"));
        LOGGER.info("NewWorldCommand: loop stopped (was {} remaining)", prev);
        return 1;
    }

    /**
     * Wall-clock tick driver for the loop. No-op while {@code loopRemaining <= 0}
     * (the common case). Skips fires when the singleplayer server is not running
     * (title screen, world load, dedicated server) so the next fire only happens
     * after the player is back in a real singleplayer world.
     */
    public static void onClientTick() {
        if (loopRemaining <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null) return;
        long now = System.currentTimeMillis();
        if (now < loopNextFireMillis) return;

        int iter = loopRemaining;
        loopRemaining = iter - 1;
        boolean sameSeed = loopSameSeed;
        LOGGER.info("NewWorldCommand: loop fire iter={} remaining={} sameSeed={}",
            iter, loopRemaining, sameSeed);
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(
                "[new-world loop] firing — " + loopRemaining + " left after this"), false);
        }

        DeathScreenLayoutHandler.launchWorld(
            mc.screen != null ? mc.screen : new TitleScreen(), sameSeed);

        if (loopRemaining > 0) {
            loopNextFireMillis = System.currentTimeMillis() + loopIntervalMillis;
        } else {
            LOGGER.info("NewWorldCommand: loop completed");
            loopNextFireMillis = 0L;
            loopIntervalMillis = 0L;
        }
    }
}
