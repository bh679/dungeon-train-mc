package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.world.EndGatewayExit;
import games.brennan.dungeontrain.world.NetherPortalLinks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The second half of a new End gateway trip ({@link EndGatewayBandJump}). Generating the far end on the server
 * thread stalls it long enough to time players out, so the trip sends them there at once and the chunks generate
 * in the background, as for any teleport; once they are ready, this builds the partner gateway, records the pair
 * and sets the travellers down beside it. Server thread only.
 */
@EventBusSubscriber(modid = "dungeontrain")
public final class EndGatewayArrivals {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Vanilla's own radius for a portal's chunk ticket. */
    private static final int TICKET_RADIUS = 3;

    /** How often the ticket is renewed: well inside {@link TicketType#PORTAL}'s lifetime. */
    private static final int TICKET_RENEW_TICKS = 100;

    /** Past this a far end is given up on, and the next use of the gateway tries again. */
    private static final int GIVE_UP_TICKS = 6000;

    /** A traveller further than this from the arrival point has gone elsewhere and is left alone. */
    private static final double TRAVELLER_REACH = 64.0;

    private static final class Arrival {
        final int x;
        final int z;
        @Nullable final BlockPos origin;
        final String who;
        final int fromX;
        final Set<UUID> players = new HashSet<>();
        int age;

        Arrival(int x, int z, @Nullable BlockPos origin, String who, int fromX) {
            this.x = x;
            this.z = z;
            this.origin = origin;
            this.who = who;
            this.fromX = fromX;
        }
    }

    /** Keyed by the far end's column, so travellers from one gateway share its arrival. */
    private static final Map<ChunkColumn, Arrival> PENDING = new HashMap<>();

    private record ChunkColumn(int x, int z) {}

    private EndGatewayArrivals() {}

    /**
     * Expect a traveller at column {@code x, z}. {@code origin} is the gateway to pair with, or null for one that
     * is never paired; {@code player} is null for a pearl, which lands by itself.
     */
    static void expect(ServerLevel level, int x, int z, @Nullable BlockPos origin, @Nullable ServerPlayer player,
                       String who, int fromX) {
        Arrival arrival = PENDING.computeIfAbsent(new ChunkColumn(x, z), c -> new Arrival(x, z, origin, who, fromX));
        if (player != null) arrival.players.add(player.getUUID());
        renewTicket(level, arrival);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        for (Iterator<Arrival> it = PENDING.values().iterator(); it.hasNext(); ) {
            Arrival arrival = it.next();
            arrival.age++;
            if (EndGatewayExit.isReady(level, arrival.x, arrival.z)) {
                it.remove();
                arrive(server, level, arrival);
            } else if (arrival.age >= GIVE_UP_TICKS) {
                it.remove();
                LOGGER.warn("[DungeonTrain] end gateway: far end at x={} z={} never generated", arrival.x, arrival.z);
            } else if (arrival.age % TICKET_RENEW_TICKS == 0) {
                renewTicket(level, arrival);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }

    private static void arrive(MinecraftServer server, ServerLevel level, Arrival arrival) {
        BlockPos gateway = EndGatewayExit.findOrBuild(level, arrival.x, arrival.z);
        if (arrival.origin != null) NetherPortalLinks.getEndGateway(level).link(arrival.origin, gateway);
        Vec3 landing = EndGatewayExit.landingBeside(level, gateway);
        for (UUID id : arrival.players) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || player.level() != level) continue;
            if (Math.abs(player.getX() - arrival.x) > TRAVELLER_REACH
                    || Math.abs(player.getZ() - arrival.z) > TRAVELLER_REACH) continue;
            player.teleportTo(landing.x, landing.y, landing.z);
            player.resetFallDistance();
        }
        LOGGER.info("[DungeonTrain] end gateway: {} x={} → {}, built after {} ticks, pair {} ↔ {}",
                arrival.who, arrival.fromX, gateway, arrival.age, arrival.origin, gateway);
    }

    private static void renewTicket(ServerLevel level, Arrival arrival) {
        BlockPos at = new BlockPos(arrival.x, level.getSeaLevel(), arrival.z);
        level.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(at), TICKET_RADIUS, at);
    }
}
