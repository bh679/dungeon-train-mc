package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when a player's <em>lifetime</em> distance ridden (in metres, 1 block =
 * 1 metre) reaches a threshold. Backing counter is the cross-world
 * {@link GlobalPlayerStats#distanceBlocks(java.util.UUID)} accumulator, which
 * sums boarded movement across every world and session — the lifetime twin of
 * {@link RunDistanceTrigger}.
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:lifetime_distance",
 *   "conditions": { "thresholdMeters": 100000 } }
 * }</pre>
 */
public final class LifetimeDistanceTrigger extends SimpleCriterionTrigger<LifetimeDistanceTrigger.Instance> {

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
