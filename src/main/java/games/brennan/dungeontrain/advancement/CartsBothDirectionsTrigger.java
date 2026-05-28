package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when a player's forward AND backward per-life carriage subtotals
 * both reach a threshold. Backing state lives in
 * {@link games.brennan.dungeontrain.player.PlayerRunState} —
 * {@code cartsForwardSinceDeath()} and {@code cartsBackwardSinceDeath()}.
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

    public void trigger(ServerPlayer player, int forward, int backward) {
        trigger(player, instance -> instance.matches(forward, backward));
    }

    public record Instance(Optional<ContextAwarePredicate> player, int threshold)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.INT.optionalFieldOf("threshold", 1).forGetter(Instance::threshold)
        ).apply(in, Instance::new));

        public boolean matches(int forward, int backward) {
            return forward >= threshold && backward >= threshold;
        }
    }
}
