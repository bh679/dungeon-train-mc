package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalCarriageLayout;
import games.brennan.dungeontrain.portal.PortalRoomTiler;
import games.brennan.dungeontrain.portal.PortalRoomTiling;
import games.brennan.dungeontrain.portal.PortalStructure;
import games.brennan.dungeontrain.portal.PortalTestSession;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Grows the window of room copies around a player standing in a test dimensional carriage — what
 * {@link PortalCarriageEvents}'s room tiling does for a live pair, for a structure that is not one.
 *
 * <p><b>The real tiler, driven differently.</b> {@link PortalRoomTiler#tick} takes everything it
 * needs as arguments and reads no live-pair state, so the only thing a test structure lacks is
 * somebody to call it. Re-implementing the window here would mean an author inspecting copies that
 * are not the copies they will ship; calling the same method means what they walk through is what
 * the live path would build.</p>
 *
 * <p>Block variants come along with it for the same reason: a copy's variant is
 * {@code PortalStructure.variantIndexFor(tile, pairKey)}, resolved inside the tiler.</p>
 *
 * <p><b>Neighbours are empty.</b> A test structure stands alone in its own reserved band, so there
 * is no other structure for a copy to be stamped onto.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalTestTicker {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PortalTestTicker() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;

        Set<Map.Entry<UUID, PortalTestSession.Session>> trips = PortalTestSession.entries();
        if (trips.isEmpty()) return;

        MinecraftServer server = level.getServer();
        CarriageDims dims = DungeonTrainWorldData.get(server.overworld()).dims();

        for (Map.Entry<UUID, PortalTestSession.Session> trip : trips) {
            ServerPlayer player = server.getPlayerList().getPlayer(trip.getKey());
            if (player == null) continue;

            PortalStructure structure = trip.getValue().structure();
            PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());

            // Which tile they are standing on, or nothing when they have walked out of the window
            // entirely — an empty set is the drain the live path uses, so a test structure sheds its
            // copies the same way rather than holding them for as long as the session lasts.
            Set<PortalRoomTiling.Tile> standingIn = occupiedTiles(player, dims, layout, structure);

            PortalStructure next = PortalRoomTiler.tick(level, dims, structure, standingIn,
                PortalRoomTiling.MAX_RADIUS, List.of(), PortalTestSession.PAIR_KEY);
            if (next != structure) {
                PortalTestSession.updateStructure(trip.getKey(), next);
                // Nothing on the tiling path logs, so a run that grew a window and a run that grew
                // nothing looked identical afterwards. One line per fold, and a fold is at most one
                // per tick, so this stays quiet on a room that does not tile.
                LOGGER.info("[DungeonTrain] portal test: tiled '{}' for {} — {} tile(s) standing",
                    next.roomName(), player.getName().getString(), next.tiling().tiles().size());
            }

            // The same fog, sky and train audio a live room sends its occupants, through the same
            // senders — a room inspected under different light is a room inspected wrong.
            PortalCarriageEvents.sendRoomAmbience(dims, layout, next, List.of(player));
        }
    }

    /**
     * The tile this player occupies, as a set so it reads the way the tiler's parameter does.
     *
     * <p>Bounded by the room's own tiled span rather than by a fixed box: the window is what they
     * are walking through, and a player past its edge is the signal to drain.</p>
     */
    private static Set<PortalRoomTiling.Tile> occupiedTiles(ServerPlayer player, CarriageDims dims,
                                                            PortalCarriageLayout layout,
                                                            PortalStructure structure) {
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();

        int minX = Math.min(structure.origin().getX() - 1, structure.tiledMinX(dims, layout) - 1);
        int maxX = Math.max(structure.origin().getX() + structure.spanX(dims) + 1,
            structure.tiledMaxX(dims, layout) + 2);
        int minZ = Math.min(structure.origin().getZ() - 1, structure.tiledMinZ(dims, layout) - 1);
        int maxZ = Math.max(structure.origin().getZ() + dims.width() + 1,
            structure.tiledMaxZ(dims, layout) + 2);
        int minY = structure.origin().getY() - 1;
        int maxY = structure.origin().getY() + Math.max(dims.height(), structure.roomSize().getY()) + 2;

        if (x < minX || x > maxX || z < minZ || z > maxZ || y < minY || y > maxY) return Set.of();
        return Set.of(structure.tileAt(dims, layout, x, z));
    }
}
