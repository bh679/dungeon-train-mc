package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when the absolute global travelled-carriage counter reaches a
 * threshold. Backing value is
 * {@code Math.abs(BoardingProgressData.travelledCarriageIndex())} — the
 * same counter
 * {@link games.brennan.dungeontrain.difficulty.DifficultyApplier} uses for
 * mob-difficulty tiering, so the achievement and the on-screen difficulty
 * progression are coherent and survive deaths.
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:carts_in_run",
 *   "conditions": { "threshold": 1000 } }
 * }</pre>
 */
public final class CartsInRunTrigger extends SimpleCriterionTrigger<CartsInRunTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player, int currentCount) {
        trigger(player, instance -> instance.matches(currentCount));
    }

    public record Instance(Optional<ContextAwarePredicate> player, int threshold)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.INT.optionalFieldOf("threshold", 1).forGetter(Instance::threshold)
        ).apply(in, Instance::new));

        public boolean matches(int currentCount) {
            return currentCount >= threshold;
        }
    }
}
