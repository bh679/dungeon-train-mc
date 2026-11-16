package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.NetherPortalBandJump;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Keeps a player in a portal while the far end of their trip loads. Finding or building an exit
 * ({@code event/NetherPortalBandJump}) reads the chunks around it on the server thread, so an exit in new terrain
 * would generate there, for long enough to time players out. Instead the chunks are asked for in the background and
 * the trip runs when they are ready. The player is still standing in the portal meanwhile; walking out cancels.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalExitWait {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Blocks around the asked-for exit a trip can touch: vanilla's 16-block search for a spot, plus the frame. */
    static final int EXIT_REACH = 20;

    /** A player this far (squared) from where they stepped in has walked out of the portal. */
    static final double WALKED_OUT_SQR = 36.0;

    /** Past this the wait is dropped. The chunks carry on generating, so the next try picks up from there. */
    static final long TIMEOUT_TICKS = 1200L;

    /** Vanilla's own radius for a portal's chunk ticket; reaches past {@link #EXIT_REACH}. */
    private static final int TICKET_RADIUS = 3;

    /** How often the ticket is renewed: well inside {@link TicketType#PORTAL}'s lifetime. */
    private static final long TICKET_RENEW_TICKS = 100L;

    /** What a wait does next. */
    enum Step { WAIT, TRAVEL, CANCEL, GIVE_UP }

    private record Waiting(BlockPos exit, BlockPos portalPos, Block portal, Vec3 stoodAt, boolean onCarriage,
                           NetherPortalBandJump.Kind kind, NetherPortalBandJump.ExitPortalFinder finder, long startTick) {}

    private static final Map<UUID, Waiting> WAITING = new HashMap<>();

    private PortalExitWait() {}

    /** The rule: leaving ends the wait first, then ready chunks send the player on, then the timeout gives up. */
    static Step decide(boolean stillThere, boolean exitReady, long waitedTicks) {
        if (!stillThere) return Step.CANCEL;
        if (exitReady) return Step.TRAVEL;
        return waitedTicks >= TIMEOUT_TICKS ? Step.GIVE_UP : Step.WAIT;
    }

    /** Whether every chunk within {@link #EXIT_REACH} of column {@code x, z} passes {@code loaded}. */
    static boolean exitChunksPass(int x, int z, BiPredicate<Integer, Integer> loaded) {
        for (int cx = (x - EXIT_REACH) >> 4; cx <= (x + EXIT_REACH) >> 4; cx++) {
            for (int cz = (z - EXIT_REACH) >> 4; cz <= (z + EXIT_REACH) >> 4; cz++) {
                if (!loaded.test(cx, cz)) return false;
            }
        }
        return true;
    }

    /**
     * Whether a player moved {@code dx, dy, dz} from where they stepped in has left the portal. A frame on a carriage
     * moves along the track with the player, so there only sideways and vertical movement can tell.
     */
    static boolean walkedOut(double dx, double dy, double dz, boolean onCarriage) {
        double along = onCarriage ? 0.0 : dx * dx;
        return along + dy * dy + dz * dz > WALKED_OUT_SQR;
    }

    /**
     * True when {@code player}'s trip must wait for {@code exit} to load, which starts the wait if it isn't running;
     * false when the exit is ready and the trip can go ahead.
     */
    public static boolean holds(ServerLevel level, ServerPlayer player, BlockPos exit, BlockPos portalPos,
                                boolean onCarriage, NetherPortalBandJump.Kind kind,
                                NetherPortalBandJump.ExitPortalFinder finder) {
        if (isReady(level, exit)) {
            WAITING.remove(player.getUUID());
            return false;
        }
        if (!WAITING.containsKey(player.getUUID())) {
            WAITING.put(player.getUUID(), new Waiting(exit.immutable(), portalPos.immutable(),
                    level.getBlockState(portalPos).getBlock(), player.position(), onCarriage, kind, finder,
                    level.getGameTime()));
            renewTicket(level, exit);
            LOGGER.info("[DungeonTrain] portal: {} waiting at x={} for the exit near {} to load",
                    player.getName().getString(), player.getBlockX(), exit);
        }
        return true;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (WAITING.isEmpty()) return;
        ServerLevel level = event.getServer().overworld();
        for (Map.Entry<UUID, Waiting> entry : new ArrayList<>(WAITING.entrySet())) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            progress(level, entry.getKey(), player, entry.getValue());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        WAITING.clear();
    }

    private static void progress(ServerLevel level, UUID id, ServerPlayer player, Waiting waiting) {
        long waited = level.getGameTime() - waiting.startTick();
        boolean stillThere = player != null && player.level() == level && !player.isRemoved() && player.isAlive()
                && level.getBlockState(waiting.portalPos()).is(waiting.portal())
                && !hasWalkedOut(player, waiting);
        switch (decide(stillThere, isReady(level, waiting.exit()), waited)) {
            case WAIT -> {
                if (waited % TICKET_RENEW_TICKS == 0L) renewTicket(level, waiting.exit());
            }
            case CANCEL -> WAITING.remove(id);
            case GIVE_UP -> {
                WAITING.remove(id);
                LOGGER.warn("[DungeonTrain] portal: the exit near {} did not load in {} ticks", waiting.exit(), waited);
            }
            case TRAVEL -> {
                WAITING.remove(id);
                LOGGER.info("[DungeonTrain] portal: {} waited {} ticks for the exit near {}",
                        player.getName().getString(), waited, waiting.exit());
                travel(level, player, waiting);
            }
        }
    }

    private static void travel(ServerLevel level, ServerPlayer player, Waiting waiting) {
        NetherPortalBandJump.Outcome outcome = waiting.kind() == NetherPortalBandJump.Kind.OTHERSIDE
                ? NetherPortalBandJump.othersideDestination(level, player, waiting.portalPos())
                : NetherPortalBandJump.destination(level, player, waiting.portalPos(), waiting.kind(), waiting.finder());
        DimensionTransition transition = outcome.transition();
        if (transition != null) player.changeDimension(transition);
    }

    private static boolean hasWalkedOut(ServerPlayer player, Waiting waiting) {
        Vec3 stoodAt = waiting.stoodAt();
        return walkedOut(player.getX() - stoodAt.x, player.getY() - stoodAt.y, player.getZ() - stoodAt.z,
                waiting.onCarriage());
    }

    private static boolean isReady(ServerLevel level, BlockPos exit) {
        return exitChunksPass(exit.getX(), exit.getZ(), (cx, cz) -> level.getChunkSource().getChunkNow(cx, cz) != null);
    }

    private static void renewTicket(ServerLevel level, BlockPos exit) {
        level.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(exit), TICKET_RADIUS, exit);
    }
}
