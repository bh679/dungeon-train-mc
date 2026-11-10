package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.util.DiskSpace;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Warns the player, with a toast and a chat line, when the drive holding the game folder (and so
 * their saves) is nearly full, before a full drive crashes the game mid-run. Thresholds and cadence
 * live in {@link DiskSpace}.
 *
 * <p>Client-side only, so it covers singleplayer (the integrated server writes to the same drive)
 * and a player's own drive on any server. Dedicated servers watch their own drive in
 * {@code event.ServerLowDiskSpaceWarning}. The drive is read off the render thread.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LowDiskSpaceWarning {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long CHECK_INTERVAL_MS = 30_000L;

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private static long lastCheckMs;
    private static DiskSpace.Alarm alarm = DiskSpace.Alarm.START;

    private LowDiskSpaceWarning() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        lastCheckMs = 0; // check as soon as the world is up
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        alarm = DiskSpace.Alarm.START; // a new session warns again
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long now = System.currentTimeMillis();
        if (now - lastCheckMs < CHECK_INTERVAL_MS || !IN_FLIGHT.compareAndSet(false, true)) return;
        lastCheckMs = now;

        Path gameDir = mc.gameDirectory.toPath();
        boolean dev = DungeonTrain.isDevBuild();
        CompletableFuture.supplyAsync(() -> DiskSpace.usableBytes(gameDir, dev), Util.ioPool())
            .whenCompleteAsync((free, error) -> {
                IN_FLIGHT.set(false);
                if (free != null) onReading(free, gameDir);
            }, mc);
    }

    private static void onReading(OptionalLong free, Path gameDir) {
        if (free.isEmpty()) return;
        long bytes = free.getAsLong();
        DiskSpace.Alarm.Step step = alarm.next(bytes, System.currentTimeMillis());
        alarm = step.alarm();
        if (step.announce() == DiskSpace.Level.OK) return;
        LOGGER.warn("[DungeonTrain] Low disk space ({}): {} free on the drive holding {}",
            step.announce(), DiskSpace.format(bytes), gameDir);
        announce(step.announce() == DiskSpace.Level.CRITICAL, DiskSpace.format(bytes));
    }

    private static void announce(boolean critical, String free) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (critical) {
            SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable("toast.dungeontrain.low_disk.critical_title").withStyle(ChatFormatting.RED),
                Component.translatable("toast.dungeontrain.low_disk.critical_body", free));
            player.displayClientMessage(Component.translatable("chat.dungeontrain.low_disk.critical", free)
                .withStyle(ChatFormatting.RED), false);
        } else {
            SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable("toast.dungeontrain.low_disk.title"),
                Component.translatable("toast.dungeontrain.low_disk.body", free));
            player.displayClientMessage(Component.translatable("chat.dungeontrain.low_disk", free)
                .withStyle(ChatFormatting.GOLD), false);
        }
    }
}
