package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.editor.EditorWorldLayout;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Whether a bed or respawn anchor offers a nap: save the player, quit, and delete the world
 * ({@link NapService}).
 *
 * <p>Singleplayer only for now: the integrated server's owner, alone on it (no LAN guests), in a
 * real run world (one that starts with a train, not the Train Builder or Editor). The spot must be
 * natural ({@link PlacedRestTracker}) and must work where it stands now ({@link HostileBandBeds}): a
 * bed that would explode, in a Nether or End core, never offers a nap, and an anchor only offers one
 * in the Nether. A carriage bed asleep as the train rolls into the Nether loses its night prompt.</p>
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

    /** Whether the bed standing at {@code pos} counts as natural and sleeps (rather than exploding) here. */
    public static boolean isNaturalBed(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)) return false;
        PlacedRestTracker placed = PlacedRestTracker.get(level);
        if (placed.isPlayerPlaced(pos) || placed.isPlayerPlaced(PlacedRestTracker.otherHalf(pos, state))) return false;
        return !HostileBandBeds.bedExplodes(level, pos);
    }

    /** Whether the respawn anchor at {@code pos} counts as natural and works (rather than exploding) here. */
    public static boolean isNaturalAnchor(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof RespawnAnchorBlock)) return false;
        return !PlacedRestTracker.get(level).isPlayerPlaced(pos) && HostileBandBeds.anchorWorks(level, pos);
    }

    /** Both halves: a natural bed or anchor that works here, in a run that allows a nap. */
    public static boolean canNap(ServerPlayer player, BlockPos spot) {
        if (!runAllowsNap(player)) return false;
        ServerLevel level = player.serverLevel();
        return isNaturalBed(level, spot) || isNaturalAnchor(level, spot);
    }

    private static boolean isBuilderWorld(ServerLevel level) {
        try {
            return level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE);
        } catch (Throwable t) {
            return false;
        }
    }
}
