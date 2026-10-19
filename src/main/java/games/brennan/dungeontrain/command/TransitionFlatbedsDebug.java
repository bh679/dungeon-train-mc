package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.train.TransitionFlatbedPlan;
import games.brennan.dungeontrain.train.TransitionFlatbeds;
import games.brennan.dungeontrain.worldgen.TransitionLandmarks;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /dungeontrain debug transition-flatbeds} — every transition landmark of the lead rider's run and
 * the next, with its world X and the carriage(s) it claimed — or, while still pending, the
 * carriage the lead rider is predicted to be standing in when they reach it. Logged at INFO as well.
 */
final class TransitionFlatbedsDebug {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TransitionFlatbedsDebug() {}

    static int report(CommandSourceStack source) {
        ServerLevel overworld = source.getServer().overworld();
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (!cycle.hasLayout()) {
            send(source, "[DungeonTrain] transition-flatbeds: classic cycle order — no landmarks", ChatFormatting.AQUA);
            return 1;
        }
        if (Trains.registeredTrainIds().isEmpty()) {
            send(source, "[DungeonTrain] transition-flatbeds: no train", ChatFormatting.AQUA);
            return 1;
        }
        for (UUID trainId : Trains.registeredTrainIds()) {
            List<Trains.Carriage> train = Trains.findById(overworld, trainId);
            TrainTransformProvider provider = train.isEmpty() ? null : Trains.lead(train).provider();
            TransitionFlatbedPlan.Lead lead = TransitionFlatbeds.lead(trainId, overworld.getGameTime());
            double speed = provider == null ? 0.0 : provider.getTargetVelocity().x();
            double pitch = 0.0;
            if (provider != null) {
                CarriageDims dims = provider.dims();
                int g = Math.max(1, provider.getGroupSize());
                pitch = (g * dims.length() + 2.0 * CarriagePlacer.halfPadLen(dims)) / g;
            }
            send(source, "[DungeonTrain] transition-flatbeds train " + trainId + ": "
                    + (lead == null ? "no rider" : String.format("lead pIdx=%d x=%.1f pace=%.3f c/s", lead.pIdx(), lead.worldX(), lead.pace()))
                    + String.format(" speed=%.2f b/s", speed), ChatFormatting.AQUA);
            int run = lead == null ? 0 : cycle.runIndexAt((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, lead.worldX())));
            List<TransitionLandmarks.Landmark> landmarks = new ArrayList<>(TransitionLandmarks.forRun(cycle, run));
            landmarks.addAll(TransitionLandmarks.forRun(cycle, run + 1));
            Map<String, int[]> claims = TransitionFlatbeds.claims(overworld, trainId);
            for (TransitionLandmarks.Landmark lm : landmarks) {
                int[] claim = claims.get(lm.id());
                String state;
                ChatFormatting colour;
                if (claim != null) {
                    state = "claimed " + Arrays.toString(claim);
                    colour = ChatFormatting.GREEN;
                } else if (lead != null && lead.worldX() >= lm.worldX()) {
                    state = "passed";
                    colour = ChatFormatting.DARK_GRAY;
                } else if (lead != null) {
                    int target = TransitionFlatbedPlan.predictTarget(lead.pIdx(), lead.worldX(), lm.worldX(), speed, lead.pace(), pitch);
                    state = "pending, predicted pIdx " + (target == TransitionFlatbedPlan.UNREACHABLE ? "never" : target);
                    colour = ChatFormatting.WHITE;
                } else {
                    state = "pending";
                    colour = ChatFormatting.WHITE;
                }
                send(source, String.format("    %-18s X %-9d x%d  %s", lm.id(), lm.worldX(), lm.count(), state), colour);
            }
        }
        return 1;
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
