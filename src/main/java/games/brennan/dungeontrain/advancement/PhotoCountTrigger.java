package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when a photo adds to one of a player's photo tallies — how many photos had an animal, a
 * hostile mob or a passenger in frame ({@code compat.photo.PhotoCounts}). A criterion matches once
 * its {@code category}'s tally reaches {@code threshold}.
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:photo_count",
 *   "conditions": { "category": "animal", "threshold": 50 } }
 * }</pre>
 */
public final class PhotoCountTrigger extends SimpleCriterionTrigger<PhotoCountTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player, String category, int count) {
        trigger(player, instance -> instance.matches(category, count));
    }

    public record Instance(Optional<ContextAwarePredicate> player, String category, int threshold)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.STRING.fieldOf("category").forGetter(Instance::category),
            Codec.INT.optionalFieldOf("threshold", 1).forGetter(Instance::threshold)
        ).apply(in, Instance::new));

        public boolean matches(String counted, int count) {
            return category.equals(counted) && count >= threshold;
        }
    }
}
