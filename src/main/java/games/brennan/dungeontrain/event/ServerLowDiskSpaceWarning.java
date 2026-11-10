package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.util.DiskSpace;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dedicated-server half of the low-storage warning: when the drive holding the world is nearly
 * full, log a WARN and tell the ops in chat, since a full server drive crashes everyone's run.
 * Integrated servers are skipped; the player's own client warns them ({@code client.LowDiskSpaceWarning}).
 * Thresholds and cadence live in {@link DiskSpace}; the drive is read off the server thread.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ServerLowDiskSpaceWarning {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long CHECK_INTERVAL_MS = 30_000L;
    private static final int OP_PERMISSION_LEVEL = 2;

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private static long lastCheckMs;
    private static DiskSpace.Alarm alarm = DiskSpace.Alarm.START;

    private ServerLowDiskSpaceWarning() {}

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        alarm = DiskSpace.Alarm.START;
        lastCheckMs = 0;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!server.isDedicatedServer()) return;
        long now = System.currentTimeMillis();
        if (now - lastCheckMs < CHECK_INTERVAL_MS || !IN_FLIGHT.compareAndSet(false, true)) return;
        lastCheckMs = now;

        Path worldDir = server.getWorldPath(LevelResource.ROOT);
        boolean dev = DungeonTrain.isDevBuild();
        CompletableFuture.supplyAsync(() -> DiskSpace.usableBytes(worldDir, dev), Util.ioPool())
            .whenCompleteAsync((free, error) -> {
                IN_FLIGHT.set(false);
                if (free != null) onReading(server, free, worldDir);
            }, server);
    }

    private static void onReading(MinecraftServer server, OptionalLong free, Path worldDir) {
        if (free.isEmpty()) return;
        long bytes = free.getAsLong();
        DiskSpace.Alarm.Step step = alarm.next(bytes, System.currentTimeMillis());
        alarm = step.alarm();
        if (step.announce() == DiskSpace.Level.OK) return;
        String formatted = DiskSpace.format(bytes);
        LOGGER.warn("[DungeonTrain] Low disk space ({}): {} free on the drive holding {}. "
            + "If it fills up the server will crash.", step.announce(), formatted, worldDir);
        ChatFormatting colour = step.announce() == DiskSpace.Level.CRITICAL ? ChatFormatting.RED : ChatFormatting.GOLD;
        Component line = Component.translatable("chat.dungeontrain.low_disk.server", formatted).withStyle(colour);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(OP_PERMISSION_LEVEL)) player.sendSystemMessage(line);
        }
    }
}
