package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when a player's <em>single-life</em> time aboard the train (in server
 * ticks) reaches a threshold. Backing counter is the per-run, boarded-only,
 * reset-on-death {@link games.brennan.dungeontrain.player.PlayerRunState#trainTimeTicks}
 * — the single-life twin of the cross-world {@link TrainTimeTrigger}.
 *
 * <p>Thresholds are stored as long ticks (1 second = 20 ticks). For reference:
 * 1 hour = 72,000 ticks; 2 hours = 144,000 ticks.</p>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:run_train_time",
 *   "conditions": { "thresholdTicks": 72000 } }
 * }</pre>
 */
public final class RunTrainTimeTrigger extends SimpleCriterionTrigger<RunTrainTimeTrigger.Instance> {

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
