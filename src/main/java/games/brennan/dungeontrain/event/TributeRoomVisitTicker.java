package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.tribute.TributeRoomCamera;
import games.brennan.dungeontrain.portal.tribute.TributeRoomVisitSessions;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per tick, for every tribute room visit: the room's own fog, sky and depth disguise (the same senders a
 * live room uses, as {@link PortalTestTicker} does for a test), and a visitor who somehow left the
 * structure is stood back in front of the TV.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TributeRoomVisitTicker {

    private TributeRoomVisitTicker() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (TributeRoomVisitSessions.isEmpty()) return;
        MinecraftServer server = level.getServer();
        DungeonTrainWorldData worldData = DungeonTrainWorldData.get(level);
        CarriageDims dims = worldData.dims();
        int groundY = worldData.getTrainY();

        for (Map.Entry<UUID, TributeRoomVisitSessions.Session> entry : TributeRoomVisitSessions.entries()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            TributeRoomVisitSessions.Session session = entry.getValue();
            if (player.level() == level && !session.window().isInside(player.blockPosition()) && player.isAlive()) {
                TributeRoomCamera.Pose pose = session.pose();
                player.teleportTo(level, pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
            }
            PortalCarriageEvents.sendRoomAmbience(dims,
                PortalCarriageBuilder.layoutFor(dims, session.structure().kind()), session.structure(), groundY, List.of(player));
        }
    }
}
