package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when the number of distinct biomes a player has ridden through in the
 * current life reaches a threshold. Backing value is
 * {@code PlayerBiomeProgress.biomeCount()}; resets on death. Drives the
 * exploration count tiers ("Far Afield" 5, "Many Lands" 12, "World Without
 * End" 20).
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:biomes_visited",
 *   "conditions": { "threshold": 12 } }
 * }</pre>
 */
public final class BiomesVisitedTrigger extends SimpleCriterionTrigger<BiomesVisitedTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player, int distinctBiomes) {
        trigger(player, instance -> instance.matches(distinctBiomes));
    }

    public record Instance(Optional<ContextAwarePredicate> player, int threshold)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.INT.optionalFieldOf("threshold", 1).forGetter(Instance::threshold)
        ).apply(in, Instance::new));

        public boolean matches(int distinctBiomes) {
            return distinctBiomes >= threshold;
        }
    }
}
