package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when a player's cumulative random-book read count reaches a
 * threshold. Backing counter lives in {@link GlobalPlayerStats}
 * ({@code randomBooksRead}). Re-reads count — each held right-click
 * on a random-book item increments by one.
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:random_books_read",
 *   "conditions": { "thresholdReads": 10 } }
 * }</pre>
 */
public final class RandomBooksReadTrigger extends SimpleCriterionTrigger<RandomBooksReadTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player, long currentReads) {
        trigger(player, instance -> instance.matches(currentReads));
    }

    public record Instance(Optional<ContextAwarePredicate> player, long thresholdReads)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.LONG.fieldOf("thresholdReads").forGetter(Instance::thresholdReads)
        ).apply(in, Instance::new));

        public boolean matches(long currentReads) {
            return currentReads >= thresholdReads;
        }
    }
}
