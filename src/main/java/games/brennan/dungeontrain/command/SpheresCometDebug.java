package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.event.SpheresCometEvents;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.List;

/**
 * {@code /dungeontrain debug comet [aimed|miss]} — with no argument, the comet chance at the caller's X and how
 * many are in flight; {@code aimed} / {@code miss} launch one at the nearest train, by the caller,, whatever the band.
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

    static int launch(CommandSourceStack source, boolean aimed) {
        ServerLevel level = source.getServer().overworld();
        List<Trains.Carriage> train = nearestTrain(level, source.getPosition());
        if (train == null) {
            send(source, "[DungeonTrain] comet: no train loaded", ChatFormatting.RED);
            return 0;
        }
        boolean ok = SpheresCometEvents.launch(level, train, source.getPosition(), aimed);
        send(source, "[DungeonTrain] comet " + (aimed ? "aimed at" : "missing") + " the train: "
                + (ok ? "launched" : "failed to spawn"), ok ? ChatFormatting.GOLD : ChatFormatting.RED);
        return ok ? 1 : 0;
    }

    private static List<Trains.Carriage> nearestTrain(ServerLevel level, Vec3 at) {
        List<Trains.Carriage> best = null;
        double bestDist = Double.MAX_VALUE;
        for (List<Trains.Carriage> train : Trains.byTrainId(level).values()) {
            if (train.isEmpty()) continue;
            AABBdc bb = Trains.lead(train).ship().worldAABB();
            double dx = (bb.minX() + bb.maxX()) / 2 - at.x, dz = (bb.minZ() + bb.maxZ()) / 2 - at.z;
            double d = dx * dx + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = train;
            }
        }
        return best;
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
