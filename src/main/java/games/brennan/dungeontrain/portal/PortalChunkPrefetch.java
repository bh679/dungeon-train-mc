package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.template.GateContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Asks for a chunk dimension's terrain as soon as a player is anywhere near its pair, rather than
 * when they are about to walk into it.
 *
 * <p>A pair is planned only once a player is within approach range of its corridor, and planning a
 * chunk dimension waits on its sampled ground: a 3x3 room's takes from half a second to eight. A
 * player covers approach range in about three, so the slow ones reached a corridor that did nothing
 * until the ground landed. This starts the sample at confirm range instead.</p>
 *
 * <p>It is a hint and decides nothing. The room is rolled with {@link PortalCarriageBuilder#rollRoom},
 * the very function planning rolls with, from the same inputs; planning still rolls for itself when
 * the time comes, and if anything has moved in between the sampler sees a different room name and
 * samples that one instead. A pair is prefetched once.</p>
 */
public final class PortalChunkPrefetch {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Pairs already asked for, so a pair in range does not re-roll every tick. */
    private static final Set<Integer> ASKED = ConcurrentHashMap.newKeySet();

    private PortalChunkPrefetch() {}

    /**
     * Start sampling {@code pairKey}'s terrain if its roll lands on a chunk dimension. Server thread;
     * cheap after the first call for a pair.
     *
     * @param riderDistance how far the nearest player is, for the log only
     */
    public static void ask(ServerLevel level, int pairKey, GateContext gateCtx, boolean ownShelfBoost,
                           ServerPlayer rider, double riderDistance) {
        if (!ASKED.add(pairKey)) return;
        try {
            PortalCarriageBuilder.RoomRoll roll =
                PortalCarriageBuilder.rollRoom(level, pairKey, gateCtx, ownShelfBoost, rider);
            String room = roomToPrefetch(roll.picked(),
                name -> PortalAuthorRooms.parentOf(name).isPresent(),
                name -> PortalRoomSettings.of(name).mode().generatesTerrain());
            if (room == null) return;
            LOGGER.info("[DungeonTrain] Chunk dimension pair {} ('{}') prefetched with the nearest player {} "
                + "blocks away", pairKey, room, Math.round(riderDistance));
            PortalChunkTerrain.request(level, pairKey, room);
        } catch (RuntimeException e) {
            // Only ever a head start: planning asks again at approach range whatever happens here.
            LOGGER.warn("[DungeonTrain] Chunk dimension prefetch failed for pair {}", pairKey, e);
        }
    }

    /** Whether {@code pairKey} has been prefetched already — checked before gathering a roll's inputs. */
    public static boolean asked(int pairKey) {
        return ASKED.contains(pairKey);
    }

    /**
     * Start sampling {@code pairKey}'s terrain for a room it already rolled: a pair evicted from
     * the space its twin stood in keeps its room, and its cube went with the erase.
     */
    public static void askFor(ServerLevel level, int pairKey, String roomName, double riderDistance) {
        if (!ASKED.add(pairKey)) return;
        LOGGER.info("[DungeonTrain] Chunk dimension pair {} ('{}') prefetched again with the nearest player {} "
            + "blocks away", pairKey, roomName, Math.round(riderDistance));
        PortalChunkTerrain.request(level, pairKey, roomName);
    }

    /** {@code pairKey}'s cube was dropped, so it may be asked for again. */
    static void forget(int pairKey) {
        ASKED.remove(pairKey);
    }

    /**
     * The room to sample ahead for {@code picked}, or {@code null}: its name when it generates
     * terrain, never for an author room's parent. Which author room stands is decided at planning by
     * a split that locks an author's page and remembers the pick, so it is not rolled early; and an
     * author room is a library, not a chunk dimension.
     */
    static String roomToPrefetch(PortalOwnShelves.Outcome picked, Predicate<String> isAuthorParent,
                                 Predicate<String> generatesTerrain) {
        if (picked == null || picked.roomName() == null) return null;
        String room = picked.roomName();
        if (isAuthorParent.test(room)) return null;
        return generatesTerrain.test(room) ? room : null;
    }

    /** Forget which pairs were asked for: the next world's pair keys are other rooms. */
    public static void clear() {
        ASKED.clear();
    }
}
