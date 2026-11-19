package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires once per Tribute paid, carrying whose photo it was, what the one Tribute cost in emeralds and
 * the payer's running total paid to other people's photos ({@link GlobalTributeStats#totalPaidToOthers}).
 * Drives the spending tiers on The Enchiridion — Supporting Artists → Voyeur per Tribute on someone
 * else's photo, Big Spender on the total, "Self Care" → Self-Employed Photographer on your own.
 *
 * <p>JSON shape ({@code min_cost} and {@code min_total} both default to no bar):
 * <pre>{@code
 * { "trigger": "dungeontrain:photo_tribute",
 *   "conditions": { "whose": "own", "min_cost": 180 } }
 * { "trigger": "dungeontrain:photo_tribute",
 *   "conditions": { "whose": "others", "min_total": 100 } }
 * }</pre>
 */
public final class PhotoTributeTrigger extends SimpleCriterionTrigger<PhotoTributeTrigger.Instance> {

    /** A photo the payer took themselves. */
    public static final String OWN = "own";
    /** A photo someone else took. */
    public static final String OTHERS = "others";

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player, boolean ownPhoto, int cost, int totalToOthers) {
        String whose = ownPhoto ? OWN : OTHERS;
        trigger(player, instance -> instance.matches(whose, cost, totalToOthers));
    }

    public record Instance(Optional<ContextAwarePredicate> player, String whose, int minCost, int minTotal)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.STRING.fieldOf("whose").forGetter(Instance::whose),
            Codec.INT.optionalFieldOf("min_cost", 1).forGetter(Instance::minCost),
            Codec.INT.optionalFieldOf("min_total", 0).forGetter(Instance::minTotal)
        ).apply(in, Instance::new));

        public boolean matches(String paidWhose, int cost, int totalToOthers) {
            return whose.equals(paidWhose) && cost >= minCost && totalToOthers >= minTotal;
        }
    }
}
