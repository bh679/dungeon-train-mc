package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PortalLoadScreenPacket;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.NetherPortalLinks;
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
 * A Nether portal on the overworld ride is a portal <em>along the track</em>. A portal that already has a
 * partner ({@link NetherPortalLinks}) takes the player back to it exactly, like a vanilla pair; any other
 * portal lets the player out of a new portal at the proportional spot in the destination band's core
 * ({@link NetherPortalJump}: first overworld → first Nether; any later non-Nether band → the previous
 * Nether; from the Nether → the band before it), {@link #SIDE_OFFSET} blocks off to the side of the track
 * so it never sits on the rails or in a tunnel. Everything else is vanilla: the normal time standing in
 * the portal, the exit portal found or built by {@code PortalForcer}, the portal sound and chunk ticket,
 * no train respawn, no chat. Called from {@code mixin/NetherPortalBlockBandJumpMixin} at the head of
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

    /** Blocks sideways from the track centre a new exit portal is asked for — clear of the rails and tunnels. */
    static final int SIDE_OFFSET = 48;

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
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (!data.startsWithTrain()) return Outcome.VANILLA;

        String who = player.getName().getString();
        int fromX = player.getBlockX();
        NetherPortalLinks links = NetherPortalLinks.get(level);
        BlockPos origin = NetherPortalLinks.frameKey(level, portalPos);
        BlockPos partner = links.partnerOf(level, origin);

        BlockPos exit;
        String why;
        if (partner != null) {
            exit = partner;
            why = "linked portal";
        } else {
            OptionalLong target = NetherPortalJump.targetX(WorldGenCycle.fromConfig(), x -> BandLabel.bandAt(level, x), fromX);
            if (target.isEmpty()) {
                LOGGER.info("[DungeonTrain] nether portal at x={} by {}: no band to go to — vanilla portal", fromX, who);
                return Outcome.VANILLA;
            }
            exit = level.getWorldBorder().clampToBounds(target.getAsLong(), player.getY(), sideOfTrack(data, player.getZ()));
            why = "to " + BandLabel.bandAt(level, (int) target.getAsLong()) + " core";
        }

        DimensionTransition transition = finder.find(level, player, portalPos, exit, false, level.getWorldBorder());
        if (transition == null) {
            LOGGER.warn("[DungeonTrain] nether portal at x={} by {}: no exit portal could be placed near {}", fromX, who, exit);
            return new Outcome(true, null);
        }
        BlockPos arrived = NetherPortalLinks.frameKey(level, BlockPos.containing(transition.pos()));
        if (partner == null) links.link(origin, arrived);
        LOGGER.info("[DungeonTrain] nether portal: {} x={} ({}) → {} ({}), frames {} ↔ {}",
            who, fromX, BandLabel.bandAt(level, fromX), exit, why, origin, arrived);
        DungeonTrainNet.sendTo(player, new PortalLoadScreenPacket());
        return new Outcome(true, transition);
    }

    /** Z for a new exit portal: {@link #SIDE_OFFSET} blocks from the track centre, on the player's side of it. */
    static double sideOfTrack(DungeonTrainWorldData data, double playerZ) {
        int centre = TrackGeometry.from(data.dims(), data.getTrainY()).trackCenterZ();
        return playerZ >= centre ? centre + SIDE_OFFSET : centre - SIDE_OFFSET;
    }
}
