package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalAnchors;
import games.brennan.dungeontrain.portal.PortalBuilder;
import games.brennan.dungeontrain.portal.PortalGeometry;
import games.brennan.dungeontrain.portal.PortalRegistry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.List;

/**
 * Places hallway portals automatically along the track, on the deterministic anchor grid described
 * by {@link PortalAnchors}, so a portal turns up every {@code spacing} blocks as the train travels.
 *
 * <p><b>Runtime, not worldgen.</b> A portal footprint — a 48-block corridor plus a 15×13 pocket
 * room, stamped twice — dwarfs the 3×3 chunk window a worldgen feature is allowed to write, so
 * placement happens here as the player approaches, the way {@code TrainCarriageAppender} extends
 * the train. Nothing about world generation changes as a result.</p>
 *
 * <p><b>Never forces a chunk load.</b> A portal is stamped only once every corner of its footprint
 * is already loaded; otherwise it is left for a later tick, by which point the train's own
 * force-load window has usually caught up. Forcing {@code getChunk(FULL, true)} off the main path
 * is the known Sable deadlock (see {@code WorldgenForceGuard}), and this code has no need to go
 * near it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalSpawnEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Placement is not latency-sensitive, unlike the transit tick — once a second is plenty. */
    private static final int SCAN_PERIOD_TICKS = 20;

    /** How far ahead of a player anchors are considered. */
    private static final int LOOKAHEAD = 160;

    /** How far behind a player anchors are still considered, so one walked past still gets built. */
    private static final int LOOKBEHIND = 64;

    /**
     * Z offset of the corridor's interior from the track centre line. The carriage footprint is
     * {@link games.brennan.dungeontrain.train.CarriageDims#DEFAULT_WIDTH} (7) wide and
     * {@code TrainAssembler} converts every world block inside it into ship blocks — a portal
     * overlapping that volume would be torn into the train. 12 clears it with room to spare while
     * staying an easy step off the deck.
     */
    private static final int TRACK_Z_OFFSET = 12;

    private static final int CORRIDOR_LENGTH = 48;
    private static final int CORRIDOR_WIDTH = 3;
    private static final int CORRIDOR_HEIGHT = 3;
    private static final int COPY_DELTA_Y = 96;

    /** Keep the upper copy this far below the build ceiling. */
    private static final int CEILING_MARGIN = 4;

    private PortalSpawnEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        // Only worlds actually running the train system — a vanilla-ish DT world with no train
        // has no track to line portals up beside.
        if (!DungeonTrainWorldData.get(level).startsWithTrain()) return;

        PortalRegistry registry = PortalRegistry.get(level);
        int spacing = registry.autoSpacing();
        if (spacing < PortalAnchors.MIN_SPACING) return;

        // The world's own trainY, not the config default — a world created at a different height
        // keeps it, and the corridor floor has to sit level with that world's track bed.
        int floorY = DungeonTrainWorldData.get(level).getTrainY() - 1;

        for (ServerPlayer player : players) {
            int px = player.blockPosition().getX();
            for (int anchorX : PortalAnchors.anchorsIn(spacing, px - LOOKBEHIND, px + LOOKAHEAD)) {
                if (registry.hasPortalAt(anchorX)) continue;
                trySpawn(level, registry, anchorX, floorY);
            }
        }
    }

    private static void trySpawn(ServerLevel level, PortalRegistry registry, int anchorX, int floorY) {

        PortalGeometry geo;
        try {
            geo = new PortalGeometry(anchorX, floorY, TRACK_Z_OFFSET,
                CORRIDOR_LENGTH, CORRIDOR_WIDTH, CORRIDOR_HEIGHT, COPY_DELTA_Y);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[DungeonTrain] Skipping portal anchor {}: {}", anchorX, e.getMessage());
            return;
        }

        if (geo.highestBlockY() > level.getMaxBuildHeight() - CEILING_MARGIN
            || floorY < level.getMinBuildHeight() + 1) {
            return;
        }

        if (!footprintLoaded(level, geo)) return;

        PortalBuilder.build(level, geo);
        registry.add(geo);
        LOGGER.info("[DungeonTrain] Auto-placed hallway portal at anchor X={} (floorY={}, deltaY={})",
            anchorX, floorY, COPY_DELTA_Y);
    }

    /**
     * Whether every chunk the build will touch is already loaded. Checked at the footprint's four
     * horizontal extremes — approach, pocket end, and both Z edges — sampled every 8 blocks along X
     * so no chunk column in between is missed (a chunk is 16 wide).
     */
    private static boolean footprintLoaded(ServerLevel level, PortalGeometry geo) {
        int minX = geo.originX() - 8;
        int maxX = geo.farDoorX() + 20;          // corridor + pocket room + its far wall
        int minZ = geo.originZ() - 8;
        int maxZ = geo.originZ() + geo.width() + 8;
        int y = geo.floorYOf(PortalGeometry.COPY_NEAR);

        for (int x = minX; x <= maxX; x += 8) {
            if (!level.isLoaded(new BlockPos(x, y, minZ))) return false;
            if (!level.isLoaded(new BlockPos(x, y, maxZ))) return false;
        }
        return level.isLoaded(new BlockPos(maxX, y, minZ)) && level.isLoaded(new BlockPos(maxX, y, maxZ));
    }
}
