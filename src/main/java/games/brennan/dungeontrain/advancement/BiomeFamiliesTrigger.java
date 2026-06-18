package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when the number of distinct biome <em>families</em> a player has ridden
 * through in the current life reaches a threshold (8 = all of them). Backing
 * value is {@code PlayerBiomeProgress.familyCount()}; resets on death. Drives
 * "All Under Heaven".
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:biome_families",
 *   "conditions": { "threshold": 8 } }
 * }</pre>
 */
public final class BiomeFamiliesTrigger extends SimpleCriterionTrigger<BiomeFamiliesTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player, int distinctFamilies) {
        trigger(player, instance -> instance.matches(distinctFamilies));
    }

    public record Instance(Optional<ContextAwarePredicate> player, int threshold)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.INT.optionalFieldOf("threshold", 1).forGetter(Instance::threshold)
        ).apply(in, Instance::new));

        public boolean matches(int distinctFamilies) {
            return distinctFamilies >= threshold;
        }
    }
}
