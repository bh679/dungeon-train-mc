package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires once per Tribute paid, carrying whose photo it was and what the one Tribute cost in emeralds.
 * Drives the spending tiers on The Enchiridion — Supporting Artists → Big Spender on someone else's
 * photo, "Self Care" → Self-Employed Photographer on your own.
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:photo_tribute",
 *   "conditions": { "whose": "own", "min_cost": 180 } }
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

    public void trigger(ServerPlayer player, boolean ownPhoto, int cost) {
        String whose = ownPhoto ? OWN : OTHERS;
        trigger(player, instance -> instance.matches(whose, cost));
    }

    public record Instance(Optional<ContextAwarePredicate> player, String whose, int minCost)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.STRING.fieldOf("whose").forGetter(Instance::whose),
            Codec.INT.optionalFieldOf("min_cost", 1).forGetter(Instance::minCost)
        ).apply(in, Instance::new));

        public boolean matches(String paidWhose, int cost) {
            return whose.equals(paidWhose) && cost >= minCost;
        }
    }
}
