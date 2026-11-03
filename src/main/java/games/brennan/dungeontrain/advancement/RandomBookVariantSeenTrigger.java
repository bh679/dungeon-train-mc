package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires for one random-book variant marked seen in
 * {@link games.brennan.dungeontrain.narrative.NarrativeProgressData}. Gives a "read every variant"
 * advancement one criterion per variant, so its tooltip can show how far along the player is
 * (Faulthurst's Favourite's tooltip title bar fills; its count is hidden — see
 * {@link games.brennan.dungeontrain.compat.HiddenAdvancementCount}).
 *
 * <p>Re-fired for every seen variant after each read; vanilla ignores criteria already held.</p>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:random_book_variant_seen",
 *   "conditions": { "book": "musings_of_faulthurst", "variant": 3 } }
 * }</pre>
 */
public final class RandomBookVariantSeenTrigger extends SimpleCriterionTrigger<RandomBookVariantSeenTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player, String book, int variant) {
        trigger(player, instance -> instance.matches(book, variant));
    }

    public record Instance(Optional<ContextAwarePredicate> player, String book, int variant)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.STRING.fieldOf("book").forGetter(Instance::book),
            Codec.INT.fieldOf("variant").forGetter(Instance::variant)
        ).apply(in, Instance::new));

        public boolean matches(String seenBook, int seenVariant) {
            return book.equals(seenBook) && variant == seenVariant;
        }
    }
}
