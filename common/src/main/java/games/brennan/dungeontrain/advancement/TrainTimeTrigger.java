package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when a player's cumulative train-time (in server ticks) reaches a
 * threshold. Backing counter lives in {@link GlobalPlayerStats}.
 *
 * <p>Thresholds are stored as long ticks (1 second = 20 ticks). For
 * reference: 2 hours = 144,000 ticks; 10 hours = 720,000 ticks;
 * 24 hours = 1,728,000 ticks.</p>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:train_time",
 *   "conditions": { "thresholdTicks": 144000 } }
 * }</pre>
 */
public final class TrainTimeTrigger extends SimpleCriterionTrigger<TrainTimeTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player, long currentTicks) {
        trigger(player, instance -> instance.matches(currentTicks));
    }

    public record Instance(Optional<ContextAwarePredicate> player, long thresholdTicks)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.LONG.fieldOf("thresholdTicks").forGetter(Instance::thresholdTicks)
        ).apply(in, Instance::new));

        public boolean matches(long currentTicks) {
            return currentTicks >= thresholdTicks;
        }
    }
}
