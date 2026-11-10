package games.brennan.dungeontrain.player;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.difficulty.BoardingProgressData;
import games.brennan.dungeontrain.event.TrainRelocation;
import games.brennan.dungeontrain.net.relay.InventoryRestoreClient.Run;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * The {@code run} half of an admin inventory restore: puts a player whose run was lost (a crash, a
 * bug) back at the depth they had reached, with that run's stats, in a fresh world.
 *
 * <p>No command runs and no permission is asked for, so nothing here can make the run Free Play,
 * and {@code RUN_CHEATED} is never read or written. The move is {@link TrainRelocation} with the
 * difficulty offset left alone: difficulty follows from the restored run state, the same way it
 * does for a player who rode there.</p>
 */
public final class RunRestore {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RunRestore() {}

    /**
     * Why the run part can't be applied right now, or null when it can. Relocating replaces the
     * world's single train, so it waits until the player is alone, alive and in the overworld of a
     * train world. Pure, so it is pinned in a test.
     */
    static String blockedReason(int playersOnline, boolean inOverworld, boolean alive, boolean trainWorld) {
        if (!trainWorld) return "not_a_train_world";
        if (playersOnline > 1) return "other_players_online";
        if (!inOverworld) return "not_in_overworld";
        if (!alive) return "dead";
        return null;
    }

    static String blockedReason(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return "no_server";
        return blockedReason(server.getPlayerCount(),
            player.level().dimension() == Level.OVERWORLD,
            player.isAlive() && !player.isDeadOrDying(),
            DungeonTrainWorldData.get(server.overworld()).startsWithTrain());
    }

    /** Decode the snapshot's run state, or null (logged) when it doesn't parse. */
    static PlayerRunState decode(Run run) {
        try {
            return PlayerRunState.CODEC.parse(NbtOps.INSTANCE, TagParser.parseTag(run.runState()))
                .resultOrPartial(err -> LOGGER.warn("[DungeonTrain] Run restore: run state partly unreadable: {}", err))
                .orElse(null);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Run restore: run state unreadable: {}", e.toString());
            return null;
        }
    }

    /**
     * Restore {@code state} onto {@code player} and move them to {@code run.x()}. Returns true when
     * the move started; the stats are set either way, since they are what the player earned.
     */
    static boolean apply(ServerPlayer player, Run run, PlayerRunState state) {
        player.setData(ModDataAttachments.PLAYER_RUN_STATE.get(), state);
        // The lost run started at the world's spawn, not here.
        player.setData(ModDataAttachments.RUN_SPAWN_X.get(), 0);
        int travelled = state.travelledCarriageIndex();
        player.setData(ModDataAttachments.LONG_WAY_BACK_MAX_CARRIAGE.get(),
            Math.max(run.longWayBackMaxCarriage(), travelled));
        raiseWorldProgress(player.serverLevel(), travelled);

        TrainRelocation.Result result = TrainRelocation.relocate(player, run.x(), false);
        if (!result.ok()) {
            LOGGER.warn("[DungeonTrain] Run restore for {}: relocation to x={} failed: {}",
                player.getName().getString(), run.x(), result.failure().getString());
            return false;
        }
        LOGGER.info("[DungeonTrain] Run restore for {}: x={} carriage={} distance={}",
            player.getName().getString(), run.x(), travelled, Math.round(state.distanceBlocks()));
        return true;
    }

    /** The world's carriage counter gates room templates and contents; bring it up to the run's depth. */
    private static void raiseWorldProgress(ServerLevel level, int travelled) {
        BoardingProgressData data = BoardingProgressData.get(level);
        int delta = Math.abs(travelled) - Math.abs(data.travelledCarriageIndex());
        if (delta > 0) data.advance(delta, data.lastLeaderCarriage());
    }
}
