package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.HomeRoomReportPacket;
import games.brennan.dungeontrain.portal.home.HomeRoomBlob;
import games.brennan.dungeontrain.portal.home.HomeRoomRegistry;
import games.brennan.dungeontrain.portal.home.HomeRoomStore;
import games.brennan.dungeontrain.portal.home.HomeRoomVisitService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Walking into a home room on the train: once per entry, the owner's last death goes to the player
 * so the room's TV can replay it ({@link HomeRoomReportPacket}). The same per-player poll
 * {@code SharedRoomEnterEvents} uses for its credit line.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class HomeRoomEnterEvents {

    private static final int POLL_TICKS = 10;
    /** The room each player was last seen in, by its box's hash; 0 for none. */
    private static final Map<UUID, Integer> LAST_ROOM = new ConcurrentHashMap<>();

    private HomeRoomEnterEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || HomeRoomRegistry.isEmpty()) return;
        if (player.tickCount % POLL_TICKS != 0 || !(player.level() instanceof ServerLevel level)) return;
        HomeRoomRegistry.Standing standing = HomeRoomRegistry.at(level.dimension(), player.blockPosition());
        int key = standing == null ? 0 : standing.room().hashCode();
        Integer before = LAST_ROOM.put(player.getUUID(), key);
        if (standing == null || (before != null && before == key)) return;
        sendReport(level, player, standing);
    }

    private static void sendReport(ServerLevel level, ServerPlayer player, HomeRoomRegistry.Standing standing) {
        if (standing.owner().isEmpty()) return;
        Optional<HomeRoomBlob> blob = HomeRoomStore.loadByOwner(standing.owner());
        if (blob.isEmpty() || !blob.get().hasReport()) return;
        List<BlockPos> screen = new ArrayList<>();
        Direction facing = HomeRoomVisitService.screenBlocks(level, standing.room(), screen);
        if (screen.isEmpty()) return;
        BoundingBox box = HomeRoomVisitService.boxOf(screen);
        DungeonTrainNet.sendTo(player, HomeRoomReportPacket.of(box, facing, blob.get().ownerName(),
            blob.get().reportMessage(), blob.get().report()));
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_ROOM.remove(event.getEntity().getUUID());
    }
}
