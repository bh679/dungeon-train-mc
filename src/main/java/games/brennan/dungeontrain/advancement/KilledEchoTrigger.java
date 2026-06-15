package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when the player kills an <em>echo</em> — a PlayerMob reincarnated from a
 * fallen player (see {@link games.brennan.dungeontrain.compat.EchoIdentity}).
 * The echo-specific counterpart to <em>Murderous Intent</em> (which counts any
 * PlayerMob); killing an echo grants both.
 *
 * <p>Marker trigger: carries only the standard optional {@code player}
 * predicate. Fired from
 * {@link games.brennan.dungeontrain.event.PlayerMobAdvancementEvents} on the
 * victim's {@code LivingDeathEvent}; vanilla advancement dedupe keeps it to a
 * single grant per player.</p>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:killed_echo" }
 * }</pre>
 */
public final class KilledEchoTrigger extends SimpleCriterionTrigger<KilledEchoTrigger.Instance> {

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
