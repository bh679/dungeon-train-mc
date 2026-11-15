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
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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

    /** What stands at a spot, as far as a nap is concerned. */
    public enum Spot {
        /** Not a bed or anchor that works here (an exploding bed, an anchor outside the Nether). */
        NONE,
        /** A natural bed or anchor that works here: offers a nap. */
        NATURAL,
        /** A bed placed from a natural bed's own item. */
        MOVED,
        /** Any other player-placed bed or anchor. */
        MADE
    }

    public static Spot spotAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        PlacedRestTracker placed = PlacedRestTracker.get(level);
        if (state.getBlock() instanceof BedBlock) {
            if (HostileBandBeds.bedExplodes(level, pos)) return Spot.NONE;
            PlacedRestTracker.Kind kind = placed.kindAt(pos);
            if (kind == null) kind = placed.kindAt(PlacedRestTracker.otherHalf(pos, state));
            if (kind == null) return Spot.NATURAL;
            return kind == PlacedRestTracker.Kind.MOVED ? Spot.MOVED : Spot.MADE;
        }
        if (state.getBlock() instanceof RespawnAnchorBlock) {
            if (!HostileBandBeds.anchorWorks(level, pos)) return Spot.NONE;
            return placed.isPlayerPlaced(pos) ? Spot.MADE : Spot.NATURAL;
        }
        return Spot.NONE;
    }

    /** Both halves: a natural bed or anchor that works here, in a run that allows a nap. */
    public static boolean canNap(ServerPlayer player, BlockPos spot) {
        return runAllowsNap(player) && spotAt(player.serverLevel(), spot) == Spot.NATURAL;
    }

    /**
     * Vanilla's "monsters nearby" test for resting at {@code pos}: any monster within 8 blocks across
     * and 5 up or down that would keep the player awake. Creative players are never kept awake.
     */
    public static boolean monstersNearby(ServerPlayer player, BlockPos pos) {
        if (player.isCreative()) return false;
        Vec3 c = Vec3.atBottomCenterOf(pos);
        return !player.level().getEntitiesOfClass(Monster.class,
            new AABB(c.x - 8.0, c.y - 5.0, c.z - 8.0, c.x + 8.0, c.y + 5.0, c.z + 8.0),
            m -> m.isPreventingPlayerRest(player)).isEmpty();
    }

    private static boolean isBuilderWorld(ServerLevel level) {
        try {
            return level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE);
        } catch (Throwable t) {
            return false;
        }
    }
}
