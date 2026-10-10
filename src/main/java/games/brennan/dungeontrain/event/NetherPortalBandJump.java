package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PortalLoadScreenPacket;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.BandLabel;
import games.brennan.dungeontrain.worldgen.NetherPortalJump;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.DimensionTransition;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.OptionalLong;

/**
 * A Nether portal on the overworld ride is a portal <em>along the track</em>: the player steps out of
 * another Nether portal in the next Nether band — or, from inside a Nether band, in the previous one —
 * the same proportion of the way through it. Everything else is vanilla: the normal time standing in the
 * portal, the exit portal found or built by {@code PortalForcer}, the portal sound and chunk ticket, no
 * train respawn, no chat. Called from {@code mixin/NetherPortalBlockBandJumpMixin} at the head of
 * {@code NetherPortalBlock#getPortalDestination}, which hands the returned same-level transition to
 * vanilla's own {@code Entity#handlePortal} → {@code changeDimension}.
 *
 * <p>Untouched (vanilla portal into the real Nether): any dimension but the overworld — the bands are an
 * overworld construct — every non-player entity, worlds without the auto-train, and the
 * {@code netherPortalBandJump} / {@code netherTransitionEnabled} toggles being off.</p>
 *
 * <p>Because a same-dimension teleport never shows vanilla's "Loading terrain" screen, the player is
 * sent {@link PortalLoadScreenPacket} just before the transition so the client covers the chunk stream
 * with the Nether-portal loading screen instead of a view of empty space.</p>
 */
public final class NetherPortalBandJump {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Vanilla's private {@code NetherPortalBlock#getExitPortal}, reached through the mixin invoker. */
    @FunctionalInterface
    public interface ExitPortalFinder {
        @Nullable
        DimensionTransition find(ServerLevel level, Entity entity, BlockPos portalPos, BlockPos exitPos,
                                 boolean isNether, WorldBorder border);
    }

    /** {@code handled == false} → let vanilla run; otherwise {@code transition} (possibly null = nothing happens) is the answer. */
    public record Outcome(boolean handled, @Nullable DimensionTransition transition) {
        static final Outcome VANILLA = new Outcome(false, null);
    }

    private NetherPortalBandJump() {}

    /** Resolve {@code entity} entering the portal at {@code portalPos} in {@code level}. */
    public static Outcome destination(ServerLevel level, Entity entity, BlockPos portalPos, ExitPortalFinder finder) {
        if (level.dimension() != Level.OVERWORLD) return Outcome.VANILLA;
        if (!(entity instanceof ServerPlayer player)) return Outcome.VANILLA;
        if (!DungeonTrainCommonConfig.isNetherPortalBandJumpEnabled()) return Outcome.VANILLA;
        if (!DungeonTrainCommonConfig.isNetherTransitionEnabled()) return Outcome.VANILLA;
        if (!DungeonTrainWorldData.get(level).startsWithTrain()) return Outcome.VANILLA;

        int fromX = player.getBlockX();
        OptionalLong target = NetherPortalJump.targetX(WorldGenCycle.fromConfig(), x -> BandLabel.bandAt(level, x), fromX);
        if (target.isEmpty()) {
            LOGGER.info("[DungeonTrain] nether portal at x={} by {}: no Nether band ahead — vanilla portal",
                fromX, player.getName().getString());
            return Outcome.VANILLA;
        }

        WorldBorder border = level.getWorldBorder();
        BlockPos exit = border.clampToBounds(target.getAsLong(), player.getY(), player.getZ());
        DimensionTransition transition = finder.find(level, player, portalPos, exit, false, border);
        if (transition == null) {
            LOGGER.warn("[DungeonTrain] nether portal at x={} by {}: no exit portal could be placed near x={}",
                fromX, player.getName().getString(), target.getAsLong());
            return new Outcome(true, null);
        }
        LOGGER.info("[DungeonTrain] nether portal: {} x={} ({}) → x={} ({}), exit portal at {}",
            player.getName().getString(), fromX, BandLabel.bandAt(level, fromX), target.getAsLong(),
            BandLabel.bandAt(level, (int) target.getAsLong()), transition.pos());
        DungeonTrainNet.sendTo(player, new PortalLoadScreenPacket());
        return new Outcome(true, transition);
    }
}
