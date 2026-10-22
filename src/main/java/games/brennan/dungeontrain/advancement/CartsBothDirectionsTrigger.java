package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * "The Long Way Back": fires once a player has reached carriage {@code +threshold} AND carriage
 * {@code -threshold} this life, in either order. Fed the highest and lowest signed travelled-carriage
 * index of the life ({@code LONG_WAY_BACK_MAX_CARRIAGE} / {@code LONG_WAY_BACK_MIN_CARRIAGE} in
 * {@link games.brennan.dungeontrain.registry.ModDataAttachments}), so going 100 forward and walking
 * back to the start is not enough: the trip from one end to the other is at least twice the threshold.
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:carts_both_directions",
 *   "conditions": { "threshold": 100 } }
 * }</pre>
 */
public final class CartsBothDirectionsTrigger extends SimpleCriterionTrigger<CartsBothDirectionsTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player, int maxCarriage, int minCarriage) {
        trigger(player, instance -> instance.matches(maxCarriage, minCarriage));
    }

    public record Instance(Optional<ContextAwarePredicate> player, int threshold)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.INT.optionalFieldOf("threshold", 1).forGetter(Instance::threshold)
        ).apply(in, Instance::new));

        public boolean matches(int maxCarriage, int minCarriage) {
            return maxCarriage >= threshold && minCarriage <= -threshold;
        }
    }
}
