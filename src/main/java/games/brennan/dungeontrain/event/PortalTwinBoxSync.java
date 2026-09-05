package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.PortalTwinBoxesPacket;
import games.brennan.dungeontrain.portal.PortalCorridorSize;
import games.brennan.dungeontrain.portal.PortalPairIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Tells clients where the standing twin corridors are, so they can decline to predict a shulker box
 * into one — see {@link games.brennan.dungeontrain.client.portal.ClientShulkerBoxPrediction}.
 *
 * <p><b>Only when it changes.</b> {@link PortalPairIndex} is republished every tick as the train
 * moves, but the twins in it are stamped into the ground and do not move: the same pair yields the
 * same box tick after tick. So the set is rebuilt each tick — a handful of live pairs, a few
 * subtractions each — and a packet goes out only when it differs from what was last sent. In a
 * steady world that is silence.</p>
 *
 * <p>This is what makes the box affordable at all. The corridor's own box was rejected for the
 * client precisely because it "would have to be re-sent every tick as the train moved"
 * ({@link games.brennan.dungeontrain.client.ClientPortalCrossing}); a twin carries none of that
 * cost.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalTwinBoxSync {

    /** What every connected client was last told. Server thread only. */
    private static List<PortalTwinBoxesPacket.Entry> lastSent = List.of();

    private PortalTwinBoxSync() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        List<PortalTwinBoxesPacket.Entry> current = snapshot();
        // Order is stable across ticks — the pair index iterates the same map — so equality of the
        // lists is a sound test for "nothing changed" without sorting or hashing.
        if (current.equals(lastSent)) return;

        lastSent = current;
        PacketDistributor.sendToAllPlayers(new PortalTwinBoxesPacket(current));
    }

    /**
     * Bring a joining player up to date. They missed every change made before they connected, and
     * the tick above will not resend until something else moves.
     */
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, new PortalTwinBoxesPacket(snapshot()));
        }
    }

    /** A second world in the same session must not inherit the first's idea of what was sent. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        lastSent = List.of();
    }

    /**
     * The live twins as boxes.
     *
     * <p>The extent is the corridor's, not the carriage's, and comes from the same
     * {@link PortalCorridorSize#corridorLength} the pair index bounds {@code localOfTwin} with — so
     * the client's test and the server's cover exactly the same cells rather than nearly.</p>
     */
    private static List<PortalTwinBoxesPacket.Entry> snapshot() {
        List<PortalTwinBoxesPacket.Entry> out = new ArrayList<>();
        for (PortalPairIndex.Entry entry : PortalPairIndex.all()) {
            BlockPos origin = entry.twinOrigin();
            out.add(new PortalTwinBoxesPacket.Entry(
                origin.getX(), origin.getY(), origin.getZ(),
                PortalCorridorSize.corridorLength(entry.dims(), entry.kind()),
                entry.dims().height(),
                entry.dims().width()));
        }
        return List.copyOf(out);
    }
}
