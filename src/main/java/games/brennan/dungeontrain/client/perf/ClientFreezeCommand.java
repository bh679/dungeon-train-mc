package games.brennan.dungeontrain.client.perf;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.slf4j.Logger;

/**
 * {@code /dt-client-freeze <ms>}: hangs the render thread on purpose so {@link ClientStallWatchdog}
 * can be seen to dump it. Dev builds only — it is never registered in a release jar, so it needs no
 * {@code HiddenCommands}/{@code CommandAllowlist} entry.
 *
 * <p>The sleep is queued with {@link Minecraft#tell} rather than run inside the command, so the chat
 * screen has closed and the freeze happens on an ordinary in-world frame.</p>
 *
 * <p>Headless twin: {@code ./gradlew runClient -PquickWorld=… -PclientFreeze=<ms>} sets
 * {@code dungeontrain.clientFreezeMs}, and the same freeze is queued once, {@link #SEAM_DELAY_TICKS}
 * client ticks into the world — no chat typing needed for Gate 2.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ClientFreezeCommand {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_FREEZE_MS = 60_000;

    /** Headless seam ({@code -PclientFreeze=<ms>}): freeze once, this many ticks into the world. */
    private static final String SEAM_PROPERTY = "dungeontrain.clientFreezeMs";
    /** 10 s past {@link LagReportPrompt#JOIN_GRACE_MS}, so the seam's freeze is offered for sending. */
    private static final int SEAM_DELAY_TICKS = (int) (LagReportPrompt.JOIN_GRACE_MS / 50) + 200;
    private static int seamTicksInWorld;
    private static boolean seamFired;

    private ClientFreezeCommand() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (seamFired || !DungeonTrain.isDevBuild() || Minecraft.getInstance().level == null) return;
        Integer ms = Integer.getInteger(SEAM_PROPERTY);
        if (ms == null) {
            seamFired = true;
            return;
        }
        if (++seamTicksInWorld < SEAM_DELAY_TICKS) return;
        seamFired = true;
        LOGGER.info("[DungeonTrain] -PclientFreeze seam: freezing the render thread for {} ms", ms);
        queueFreeze(Math.min(Math.max(ms, 1), MAX_FREEZE_MS));
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (!DungeonTrain.isDevBuild()) return;
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dt-client-freeze")
            .then(Commands.argument("ms", IntegerArgumentType.integer(1, MAX_FREEZE_MS))
                .executes(ctx -> freeze(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "ms")))));
    }

    private static int freeze(CommandSourceStack source, int ms) {
        source.sendSystemMessage(Component.literal("Freezing the render thread for " + ms + " ms"));
        queueFreeze(ms);
        return 1;
    }

    private static void queueFreeze(int ms) {
        Minecraft.getInstance().tell(() -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
