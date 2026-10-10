package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.train.TrainJump;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.BandLabel;
import games.brennan.dungeontrain.worldgen.NetherPortalJump;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.OptionalLong;

/**
 * A Nether portal on the overworld ride is a travel device along the track, not a door to the real
 * Nether dimension: it takes the player to the <b>next</b> Nether band, or — when they are already
 * standing in one — back to the <b>previous</b> Nether band, with the train
 * ({@link TrainJump}, the {@code /dtp} mechanism). Called from
 * {@code mixin/NetherPortalBlockBandJumpMixin} at the head of
 * {@code NetherPortalBlock#getPortalDestination}, i.e. before vanilla would search or build an exit
 * portal in the Nether (and generate those chunks); by then {@code Entity#handlePortal} has already
 * armed the portal cooldown, so suppressing the destination ends the transit with no retry.
 *
 * <p>Untouched (vanilla portal): any dimension but the overworld — the bands are an overworld
 * construct, so a Nether-preset world's own Nether portals keep working as they do today — every
 * non-player entity, worlds without the auto-train, and the {@code netherPortalBandJump} /
 * {@code netherTransitionEnabled} toggles being off. A failed train spawn also falls back to vanilla
 * so the portal is never a dead block.</p>
 */
public final class NetherPortalBandJump {

    private static final Logger LOGGER = LogUtils.getLogger();

    private NetherPortalBandJump() {}

    /**
     * Handle {@code entity} entering a Nether portal in {@code level}.
     *
     * @return {@code true} when Dungeon Train took over and vanilla's destination must be suppressed
     */
    public static boolean handle(ServerLevel level, Entity entity) {
        if (level.dimension() != Level.OVERWORLD) return false;
        if (!(entity instanceof ServerPlayer player)) return false;
        if (!DungeonTrainCommonConfig.isNetherPortalBandJumpEnabled()) return false;
        if (!DungeonTrainCommonConfig.isNetherTransitionEnabled()) return false;
        if (!DungeonTrainWorldData.get(level).startsWithTrain()) return false;

        int fromX = player.getBlockX();
        OptionalLong target = NetherPortalJump.targetX(WorldGenCycle.fromConfig(), fromX);
        String from = BandLabel.bandAt(level, fromX);
        if (target.isEmpty()) {
            LOGGER.info("[DungeonTrain] nether portal at x={} ({}) by {}: no band to jump to — portal fizzles",
                fromX, from, player.getName().getString());
            player.displayClientMessage(Component.translatable("chat.dungeontrain.portal.no_previous_band"), false);
            return true;
        }

        double x = target.getAsLong();
        TrainJump.Result result = TrainJump.jumpTo(player, x, "nether portal");
        if (!(result instanceof TrainJump.Ok)) {
            LOGGER.warn("[DungeonTrain] nether portal at x={} by {}: train jump to {} failed ({}) — vanilla portal instead",
                fromX, player.getName().getString(), x, result);
            return false;
        }
        String to = BandLabel.bandAt(level, (int) x);
        LOGGER.info("[DungeonTrain] nether portal: {} jumps x={} ({}) → x={} ({})",
            player.getName().getString(), fromX, from, (int) x, to);
        player.displayClientMessage(Component.translatable("chat.dungeontrain.portal.band_jump", from, to), false);
        return true;
    }
}
