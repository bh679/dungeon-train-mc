package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.event.SpheresCometEvents;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /dungeontrain debug comet [aimed|miss|ahead|shower]} — with no argument, the comet chance at the caller's X
 * and how many are in flight; {@code aimed} / {@code miss} / {@code ahead} launch one at the nearest train, by the
 * caller, whatever the band ({@code ahead}: a miss that comes down beside the track in front); {@code shower} starts
 * a lap-0 strike's shower over the caller and brings the strike in 10 s later.
 * Logged at INFO for RCON runs.
 */
final class SpheresCometDebug {

    private static final Logger LOGGER = LogUtils.getLogger();

    private SpheresCometDebug() {}

    static int report(CommandSourceStack source) {
        int x = (int) Math.floor(source.getPosition().x);
        double chance = SpheresCometEvents.chanceAt(x);
        String every = chance > 0.0 ? String.format("about one every %.0f s", 1.0 / (chance * 20.0)) : "none here";
        send(source, String.format("[DungeonTrain] comets at x=%d: %s; %d in flight", x, every,
                SpheresCometEvents.liveCount()), ChatFormatting.AQUA);
        return 1;
    }

    static int launch(CommandSourceStack source, SpheresCometEvents.Aim aim) {
        ServerLevel level = source.getServer().overworld();
        List<Trains.Carriage> train = nearestTrain(level, source.getPosition());
        if (train == null) {
            send(source, "[DungeonTrain] comet: no train loaded", ChatFormatting.RED);
            return 0;
        }
        boolean ok = SpheresCometEvents.launch(level, train, source.getPosition(), aim);
        send(source, "[DungeonTrain] comet " + switch (aim) {
                    case HIT -> "aimed at";
                    case MISS -> "missing";
                    case MISS_AHEAD -> "missing ahead of";
                } + " the train: "
                + (ok ? "launched" : "failed to spawn"), ok ? ChatFormatting.GOLD : ChatFormatting.RED);
        return ok ? 1 : 0;
    }

    static int shower(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        ServerPlayer player = source.getPlayer();
        Map.Entry<UUID, List<Trains.Carriage>> train = nearestTrainEntry(level, source.getPosition());
        if (player == null || train == null) {
            send(source, "[DungeonTrain] comet shower: needs a player and a loaded train", ChatFormatting.RED);
            return 0;
        }
        boolean ok = SpheresCometEvents.announceStrike(level, train.getKey(), train.getValue(), player);
        send(source, "[DungeonTrain] comet shower: " + (ok ? "started, strike in 10 s" : "failed"),
                ok ? ChatFormatting.GOLD : ChatFormatting.RED);
        return ok ? 1 : 0;
    }

    private static List<Trains.Carriage> nearestTrain(ServerLevel level, Vec3 at) {
        Map.Entry<UUID, List<Trains.Carriage>> best = nearestTrainEntry(level, at);
        return best == null ? null : best.getValue();
    }

    private static Map.Entry<UUID, List<Trains.Carriage>> nearestTrainEntry(ServerLevel level, Vec3 at) {
        Map.Entry<UUID, List<Trains.Carriage>> best = null;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<UUID, List<Trains.Carriage>> entry : Trains.byTrainId(level).entrySet()) {
            List<Trains.Carriage> train = entry.getValue();
            if (train.isEmpty()) continue;
            AABBdc bb = Trains.lead(train).ship().worldAABB();
            double dx = (bb.minX() + bb.maxX()) / 2 - at.x, dz = (bb.minZ() + bb.maxZ()) / 2 - at.z;
            double d = dx * dx + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = entry;
            }
        }
        return best;
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
