package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires while a player is "running the roof" — traversing the train's
 * exterior top across carriage groups in one continuous streak, never
 * dropping inside an enclosed carriage. The backing value is the number of
 * <em>groups travelled</em> in the current uninterrupted on-roof run,
 * computed by
 * {@link games.brennan.dungeontrain.event.RoofRunEvents} as
 * {@code (maxAnchorPIdx − minAnchorPIdx) / groupSize}. Dropping inside a
 * carriage (or off the train) ends the streak and the counter restarts from
 * zero on the next roof.
 *
 * <p>Because "groups" is the unit (not raw carriages), the JSON threshold is
 * config-independent: a threshold of {@code 3} always means three groups,
 * however many carriages each group happens to hold.</p>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:roof_run_groups",
 *   "conditions": { "threshold": 3 } }
 * }</pre>
 */
public final class RoofRunGroupsTrigger extends SimpleCriterionTrigger<RoofRunGroupsTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player, int groupsRun) {
        trigger(player, instance -> instance.matches(groupsRun));
    }

    public record Instance(Optional<ContextAwarePredicate> player, int threshold)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.INT.optionalFieldOf("threshold", 1).forGetter(Instance::threshold)
        ).apply(in, Instance::new));

        public boolean matches(int groupsRun) {
            return groupsRun >= threshold;
        }
    }
}
