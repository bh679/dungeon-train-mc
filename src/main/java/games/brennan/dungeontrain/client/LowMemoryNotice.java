package games.brennan.dungeontrain.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.util.MachineSpecs;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Tells a player, once per game session, that Minecraft has been given too little memory.
 *
 * <p>Lag reports from players on the launcher-default {@code -Xmx4g} showed the heap all but full
 * (3.4–3.7 GB in use of ~3.8 GB) — the garbage collector running constantly, costing tick time and
 * frames. Players on 6 GB or more don't show it. The fix is on the player's side, in their launcher,
 * so the most useful thing the mod can do is say so.</p>
 *
 * <p>Only nags when the advice is actionable: a machine with less than ~8 GB of RAM has nothing
 * spare to give, and an unreadable reading ({@code 0} from {@link MachineSpecs}) is treated as
 * unknown, never as "low". One chat line, with a link explaining how and a "don't show again" link
 * that turns {@link ClientDisplayConfig#LOW_MEMORY_NOTICE_CHAT} off through a client command.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LowMemoryNotice {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long GIB = 1024L * 1024L * 1024L;

    /** Below this max heap the notice fires. {@code -Xmx4g} warns; {@code -Xmx6g} is quiet. */
    static final long WARN_BELOW_HEAP_BYTES = 5L * GIB;

    /**
     * The machine must have at least this much physical RAM. Half a GiB under 8 because an "8 GB"
     * machine reports roughly 7.6–7.9 GiB once firmware and integrated graphics have taken their cut.
     */
    static final long MIN_PHYSICAL_BYTES = 15L * GIB / 2;

    static final String HOW_TO_URL =
        "https://github.com/bh679/dungeon-train-mc/wiki/Installation#allocating-memory";

    static final String COMMAND = "dt-memory-notice";

    /** Once per game session: set on the first world join that got as far as deciding. */
    private static boolean shownThisSession;

    private LowMemoryNotice() {}

    /**
     * Whether a player with this much heap on this much machine should be told to allocate more.
     * Both inputs are bytes; {@code 0} (or less) means the reading was unavailable.
     */
    static boolean shouldWarn(long maxHeapBytes, long physicalBytes) {
        if (maxHeapBytes <= 0 || physicalBytes <= 0) return false;
        return maxHeapBytes < WARN_BELOW_HEAP_BYTES && physicalBytes >= MIN_PHYSICAL_BYTES;
    }

    /** Heap size as the player would say it — {@code "4"} for 4 GiB, {@code "3.5"} otherwise. */
    static String formatGb(long bytes) {
        double rounded = Math.round(bytes / (double) GIB * 10) / 10.0;
        return rounded == Math.rint(rounded)
            ? String.format(Locale.ROOT, "%.0f", rounded)
            : String.format(Locale.ROOT, "%.1f", rounded);
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (shownThisSession || !ClientDisplayConfig.isLowMemoryNoticeChatEnabled()) return;
        shownThisSession = true;
        long heap = MachineSpecs.maxHeapBytes();
        long physical = MachineSpecs.physicalMemoryBytes();
        if (!shouldWarn(heap, physical)) return;
        LOGGER.info("Low-memory notice: max heap {} bytes on {} bytes physical", heap, physical);
        event.getPlayer().displayClientMessage(message(heap), false);
    }

    private static Component message(long heapBytes) {
        MutableComponent how = Component.translatable("chat.dungeontrain.low_memory_notice.link")
            .withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, HOW_TO_URL))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(HOW_TO_URL))));
        MutableComponent dismiss = Component.translatable("chat.dungeontrain.low_memory_notice.dismiss")
            .withStyle(s -> s.withColor(ChatFormatting.GRAY).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + COMMAND + " off")));
        return Component.translatable("chat.dungeontrain.low_memory_notice", formatGb(heapBytes))
            .withStyle(ChatFormatting.GOLD)
            .append(" ")
            .append(how)
            .append(" ")
            .append(dismiss);
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(COMMAND)
            .then(Commands.literal("off").executes(ctx -> {
                ClientDisplayConfig.setLowMemoryNoticeChat(false);
                ctx.getSource().sendSystemMessage(
                    Component.translatable("chat.dungeontrain.low_memory_notice.dismissed")
                        .withStyle(ChatFormatting.GRAY));
                return 1;
            })));
    }
}
