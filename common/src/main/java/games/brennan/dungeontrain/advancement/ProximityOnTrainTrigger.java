package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when the player comes within ~4 blocks of another passenger — a
 * PlayerMob or another real player — while both are on the train. Marker
 * trigger: carries only the standard optional {@code player} predicate, no
 * extra conditions, so {@link #trigger(ServerPlayer)} unconditionally
 * satisfies any instance.
 *
 * <p>Fired from {@link games.brennan.dungeontrain.event.RunStatsEvents}'s
 * per-tick proximity scan (radius 4.0, both parties gated on-train); vanilla
 * advancement dedupe keeps it to a single grant per player. Drives the
 * "I'm Not Alone" advancement — the hub of the social subtree.</p>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:proximity_on_train" }
 * }</pre>
 */
public final class ProximityOnTrainTrigger extends SimpleCriterionTrigger<ProximityOnTrainTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player) {
        trigger(player, instance -> true);
    }

    public record Instance(Optional<ContextAwarePredicate> player)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player)
        ).apply(in, Instance::new));
    }
}
