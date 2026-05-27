package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when a player's current unique-chest streak reaches a threshold.
 * Backing state lives in {@link games.brennan.dungeontrain.player.PlayerRunState#uniqueChests}.
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:unique_chests_opened",
 *   "conditions": { "threshold": 100 } }
 * }</pre>
 */
public final class UniqueChestsOpenedTrigger extends SimpleCriterionTrigger<UniqueChestsOpenedTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    /**
     * Fire the trigger with the player's current streak count. Each
     * registered advancement instance's threshold is checked via
     * {@link Instance#matches(int)}; vanilla handles the
     * "already-granted, suppress duplicate" dedup.
     */
    public void trigger(ServerPlayer player, int currentCount) {
        trigger(player, instance -> instance.matches(currentCount));
    }

    public record Instance(Optional<ContextAwarePredicate> player, int threshold)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.INT.optionalFieldOf("threshold", 1).forGetter(Instance::threshold)
        ).apply(in, Instance::new));

        public boolean matches(int currentCount) {
            return currentCount >= threshold;
        }
    }
}
