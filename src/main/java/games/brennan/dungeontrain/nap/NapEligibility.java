package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.editor.EditorWorldLayout;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Whether a bed offers a nap: save the player, quit, and delete the world ({@link NapService}).
 *
 * <p>Singleplayer only for now: the integrated server's owner, alone on it (no LAN guests), in a
 * real run world (one that starts with a train, not the Train Builder or Editor). The bed must be
 * natural ({@link PlacedBedTracker}). Beds that would explode never get here: the real Nether and
 * End and DT's band cores blow them up before vanilla reaches the sleep attempt
 * ({@code BedBlockMixin}).</p>
 */
public final class NapEligibility {

    private NapEligibility() {}

    /** The run-level half of the rule: who is asking, and in what world. */
    public static boolean runAllowsNap(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || server.isDedicatedServer()) return false;
        if (!server.isSingleplayerOwner(player.getGameProfile())) return false;
        if (server.getPlayerCount() != 1) return false;
        if (!player.isAlive() || player.isSpectator()) return false;
        ServerLevel level = player.serverLevel();
        if (EditorWorldLayout.isEditorWorld(level) || isBuilderWorld(level)) return false;
        return DungeonTrainWorldData.get(server.overworld()).startsWithTrain();
    }

    /** Whether the bed standing at {@code pos} counts as natural. */
    public static boolean isNaturalBed(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)) return false;
        PlacedBedTracker placed = PlacedBedTracker.get(level);
        return !placed.isPlayerPlaced(pos) && !placed.isPlayerPlaced(PlacedBedTracker.otherHalf(pos, state));
    }

    /** Both halves: a natural bed in a run that allows a nap. */
    public static boolean canNap(ServerPlayer player, BlockPos bedPos) {
        return runAllowsNap(player) && isNaturalBed(player.serverLevel(), bedPos);
    }

    private static boolean isBuilderWorld(ServerLevel level) {
        try {
            return level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE);
        } catch (Throwable t) {
            return false;
        }
    }
}
