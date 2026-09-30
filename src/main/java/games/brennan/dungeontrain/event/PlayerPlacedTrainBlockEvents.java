package games.brennan.dungeontrain.event;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.ship.sable.CarriagePlotResolver;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.train.PlayerPlacedTrainBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Marks blocks a player places on a Dungeon Train carriage as player-added
 * ({@link PlayerPlacedTrainBlocks}), so they break off on collision instead of ploughing terrain
 * the way the train's own blocks do.
 *
 * <p>Sable routes a placement aimed at a carriage into the carriage's plot, so the event position
 * is a plot (shipyard) position and resolves to the carriage sub-level directly. Only a
 * {@link Player} counts — a piston, dispenser or enderman is not a passenger building.</p>
 *
 * <p>LOWEST priority so a handler that cancels the placement (builder protection, a portal seal)
 * has already run; a cancelled placement is never marked.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerPlacedTrainBlockEvents {

    private PlayerPlacedTrainBlockEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // A multi-place event (a bed, a door's two halves) is also an EntityPlaceEvent and carries
        // every cell it filled.
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (BlockSnapshot snapshot : multi.getReplacedBlockSnapshots()) {
                markIfCarriage(level, snapshot.getPos());
            }
            return;
        }
        markIfCarriage(level, event.getPos());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PlayerPlacedTrainBlocks.clear();
        games.brennan.dungeontrain.train.ForcedConnectCells.clear();
    }

    private static void markIfCarriage(ServerLevel level, BlockPos pos) {
        ServerSubLevel subLevel = CarriagePlotResolver.subLevelAt(level, new ChunkPos(pos));
        if (subLevel == null || !SableManagedShip.isDungeonTrainManaged(subLevel)) return;
        PlayerPlacedTrainBlocks.mark(subLevel, pos.immutable());
    }
}
