package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when a player's cumulative "burned a starting/random book without ever
 * opening it" count reaches a threshold. Backing counter lives in
 * {@link GlobalBookBurnStats} ({@code booksBurnedUnread}), incremented from
 * {@code games.brennan.dungeontrain.event.StartingBookEvents#onEntityJoinLevel}.
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:books_burned_unread",
 *   "conditions": { "thresholdReads": 10 } }
 * }</pre>
 */
public final class BooksBurnedUnreadTrigger extends SimpleCriterionTrigger<BooksBurnedUnreadTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player, long currentBurned) {
        trigger(player, instance -> instance.matches(currentBurned));
    }

    public record Instance(Optional<ContextAwarePredicate> player, long thresholdReads)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.LONG.fieldOf("thresholdReads").forGetter(Instance::thresholdReads)
        ).apply(in, Instance::new));

        public boolean matches(long currentBurned) {
            return currentBurned >= thresholdReads;
        }
    }
}
