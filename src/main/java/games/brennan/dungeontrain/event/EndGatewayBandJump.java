package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PortalLoadScreenPacket;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.EndGatewayExit;
import games.brennan.dungeontrain.world.NetherPortalLinks;
import games.brennan.dungeontrain.worldgen.BandLabel;
import games.brennan.dungeontrain.worldgen.NetherPortalJump;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.OptionalLong;

/**
 * An End gateway on the overworld ride leads back along the track, the way a Nether portal does
 * ({@link NetherPortalBandJump}). Gateways copied into an End band keep the exit they generated with, which on the
 * ride is a spot near its start. Instead, a gateway with a partner ({@link NetherPortalLinks#getEndGateway}) returns
 * to it; any other leads to the proportional spot in the overworld band before it
 * ({@link NetherPortalJump#previousOverworldX}), where {@link EndGatewayExit} builds its partner beside the track.
 *
 * <p>A player goes through, and so does a player's ender pearl, the usual way into a gateway; the pearl carries its
 * owner when it lands. Nothing else does: a mob or item is not flung along the line.</p>
 *
 * <p>Untouched (vanilla): any dimension but the overworld, worlds without the auto-train, and the
 * {@code netherPortalBandJump} toggle being off.</p>
 */
public final class EndGatewayBandJump {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** What vanilla gives a pearl leaving a gateway, so it drops to the ground and lands. */
    private static final Vec3 PEARL_EXIT_MOVEMENT = new Vec3(0.0, -1.0, 0.0);

    private static final String END_STRETCH = "End stretch";

    private static final NetherPortalBandJump.Outcome STAY = new NetherPortalBandJump.Outcome(true, null);

    private EndGatewayBandJump() {}

    /** Resolve {@code entity} entering the gateway at {@code gateway} in {@code level}. */
    public static NetherPortalBandJump.Outcome destination(ServerLevel level, Entity entity, BlockPos gateway) {
        if (level.dimension() != Level.OVERWORLD) return NetherPortalBandJump.Outcome.VANILLA;
        if (!DungeonTrainCommonConfig.isNetherPortalBandJumpEnabled()) return NetherPortalBandJump.Outcome.VANILLA;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (!data.startsWithTrain()) return NetherPortalBandJump.Outcome.VANILLA;
        if (!(entity instanceof ServerPlayer || isPlayersPearl(entity))) return STAY;

        NetherPortalLinks links = NetherPortalLinks.getEndGateway(level);
        boolean onCarriage = Shipyards.isInPlotGrid(level, gateway);
        BlockPos origin = gateway.immutable();
        BlockPos known = links.partner(origin);
        if (known != null) level.getChunk(known);       // so a partner that has since gone is seen to be gone
        BlockPos partner = links.partnerOf(level, origin);
        NetherPortalBandJump.ExitChoice choice = NetherPortalBandJump.chooseExit(partner != null, onCarriage,
                partner != null && Shipyards.isInPlotGrid(level, partner));
        if (choice == NetherPortalBandJump.ExitChoice.UNLINK_AND_JUMP) links.unlink(origin);

        int fromX = entity.getBlockX();
        BlockPos arrived;
        if (choice == NetherPortalBandJump.ExitChoice.LINKED) {
            arrived = partner;
        } else {
            arrived = buildExit(level, data, entity, fromX);
            if (arrived == null) {
                LOGGER.info("[DungeonTrain] end gateway at x={}: no overworld band behind it", fromX);
                return STAY;
            }
            if (!onCarriage) links.link(origin, arrived);
        }

        LOGGER.info("[DungeonTrain] end gateway: {} x={} ({}) → {} ({}), gateways {} ↔ {}",
                entity.getName().getString(), fromX, BandLabel.bandAt(level, fromX), arrived,
                BandLabel.bandAt(level, arrived.getX()), origin, arrived);
        Vec3 landing = EndGatewayExit.landingBeside(level, arrived);
        if (entity instanceof ServerPlayer player) DungeonTrainNet.sendTo(player, new PortalLoadScreenPacket());
        return new NetherPortalBandJump.Outcome(true, new DimensionTransition(level, landing,
                entity instanceof ThrownEnderpearl ? PEARL_EXIT_MOVEMENT : Vec3.ZERO,
                entity.getYRot(), entity.getXRot(), DimensionTransition.PLACE_PORTAL_TICKET));
    }

    /** The gateway at the far end of a new trip, or null when there is no overworld band to go to. */
    @Nullable
    private static BlockPos buildExit(ServerLevel level, DungeonTrainWorldData data, Entity entity, int fromX) {
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        // A column the End band's disintegration has reached still reads as the overworld, but it is breaking up
        // over the void: it counts with the End, as one stretch, and is never somewhere to arrive.
        OptionalLong target = NetherPortalJump.previousOverworldX(cycle,
                x -> cycle.endMiddleRamp(x) > 0.0 ? END_STRETCH : BandLabel.bandAt(level, x),
                x -> cycle.endMiddleRamp(x) <= 0.0 && isOverworldBand(TrainPhase.phaseAt(level, x)), fromX);
        if (target.isEmpty()) return null;
        BlockPos column = level.getWorldBorder().clampToBounds(target.getAsLong(), entity.getY(),
                NetherPortalBandJump.sideOfTrack(data, entity.getZ()));
        return EndGatewayExit.findOrBuild(level, column.getX(), column.getZ());
    }

    /** A band a gateway leads back to: the overworld stretches, and the Lost City, which is overworld terrain. */
    static boolean isOverworldBand(TrainPhase phase) {
        return phase == TrainPhase.OVERWORLD || phase == TrainPhase.LOST_CITY;
    }

    private static boolean isPlayersPearl(Entity entity) {
        return entity instanceof ThrownEnderpearl pearl && pearl.getOwner() instanceof ServerPlayer;
    }
}
