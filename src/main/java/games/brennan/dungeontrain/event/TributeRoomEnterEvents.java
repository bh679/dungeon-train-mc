package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.TributeRoomReportPacket;
import games.brennan.dungeontrain.portal.tribute.TributeRoomBlob;
import games.brennan.dungeontrain.portal.tribute.TributeRoomRegistry;
import games.brennan.dungeontrain.portal.tribute.TributeRoomPlacement;
import games.brennan.dungeontrain.portal.tribute.TributeRoomStore;
import games.brennan.dungeontrain.portal.tribute.TributeRoomVisitService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Walking into a tribute room on the train: once per entry, the owner's last death goes to the player
 * so the room's TV can replay it ({@link TributeRoomReportPacket}). The same per-player poll
 * {@code SharedRoomEnterEvents} uses for its credit line.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TributeRoomEnterEvents {

    private static final int POLL_TICKS = 10;
    /** The room each player was last seen in, by its box's hash; 0 for none. */
    private static final Map<UUID, Integer> LAST_ROOM = new ConcurrentHashMap<>();

    private TributeRoomEnterEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || TributeRoomRegistry.isEmpty()) return;
        if (player.tickCount % POLL_TICKS != 0 || !(player.level() instanceof ServerLevel level)) return;
        TributeRoomRegistry.Standing standing = TributeRoomRegistry.at(level.dimension(), player.blockPosition());
        int key = standing == null ? 0 : standing.room().hashCode();
        Integer before = LAST_ROOM.put(player.getUUID(), key);
        if (standing == null || (before != null && before == key)) return;
        sendReport(level, player, standing);
    }

    /**
     * The owner's room is read on the IO thread, and the banner and report go out back on the server
     * thread, only if the player is still in that room by then.
     */
    private static void sendReport(ServerLevel level, ServerPlayer player, TributeRoomRegistry.Standing standing) {
        if (standing.owner().isEmpty()) return;
        MinecraftServer server = level.getServer();
        TributeRoomStore.loadByOwnerAsync(standing.owner(), standing.key()).whenComplete((blob, error) -> server.execute(() -> {
            if (error != null || blob == null || blob.isEmpty() || player.hasDisconnected() || player.level() != level) return;
            TributeRoomRegistry.Standing now = TributeRoomRegistry.at(level.dimension(), player.blockPosition());
            if (now == null || !now.room().equals(standing.room())) return;
            show(level, player, standing, blob.get());
        }));
    }

    private static void show(ServerLevel level, ServerPlayer player, TributeRoomRegistry.Standing standing, TributeRoomBlob blob) {
        TributeRoomPlacement.showBanner(player, blob);
        if (!blob.hasReport()) return;
        List<BlockPos> screen = new ArrayList<>();
        Direction facing = TributeRoomVisitService.screenBlocks(level, standing.room(), screen);
        if (screen.isEmpty()) return;
        BoundingBox box = TributeRoomVisitService.boxOf(screen);
        DungeonTrainNet.sendTo(player, TributeRoomReportPacket.of(box, facing, blob.ownerName(),
            blob.reportMessage(), blob.report()));
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_ROOM.remove(event.getEntity().getUUID());
    }
}
