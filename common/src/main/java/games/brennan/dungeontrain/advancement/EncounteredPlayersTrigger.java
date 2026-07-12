package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when a player's cumulative distinct-player encounter count reaches a
 * threshold. "Encounter" = a PlayerMob came within ~16 blocks during a run;
 * counted once per distinct mob per run and accumulated across all runs and
 * worlds. Backing counter lives in {@link GlobalPlayerStats} (the
 * {@code playersEncountered} field), parallel to {@link TrainTimeTrigger} /
 * {@code trainTicks}.
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:encountered_players",
 *   "conditions": { "threshold": 1000 } }
 * }</pre>
 */
public final class EncounteredPlayersTrigger extends SimpleCriterionTrigger<EncounteredPlayersTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player, long currentCount) {
        trigger(player, instance -> instance.matches(currentCount));
    }

    public record Instance(Optional<ContextAwarePredicate> player, long threshold)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.LONG.fieldOf("threshold").forGetter(Instance::threshold)
        ).apply(in, Instance::new));

        public boolean matches(long currentCount) {
            return currentCount >= threshold;
        }
    }
}
