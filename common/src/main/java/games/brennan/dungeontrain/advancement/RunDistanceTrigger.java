package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when a player's <em>single-life</em> distance ridden (in metres, 1
 * block = 1 metre) reaches a threshold. Backing counter is the per-run,
 * boarded-only, reset-on-death
 * {@link games.brennan.dungeontrain.player.PlayerRunState#distanceBlocks} —
 * accrued from world-space movement while boarded, so it climbs at the train's
 * speed.
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:run_distance",
 *   "conditions": { "thresholdMeters": 10000 } }
 * }</pre>
 */
public final class RunDistanceTrigger extends SimpleCriterionTrigger<RunDistanceTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player, double currentMeters) {
        trigger(player, instance -> instance.matches(currentMeters));
    }

    public record Instance(Optional<ContextAwarePredicate> player, long thresholdMeters)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.LONG.fieldOf("thresholdMeters").forGetter(Instance::thresholdMeters)
        ).apply(in, Instance::new));

        public boolean matches(double currentMeters) {
            return currentMeters >= thresholdMeters;
        }
    }
}
