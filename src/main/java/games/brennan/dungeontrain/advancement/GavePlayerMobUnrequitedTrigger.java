package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when the player gives a PlayerMob an item and that mob has given the
 * player nothing in return — one-sided, unrequited giving. Backs the
 * <em>Simon's Desperation</em> advancement. (If the mob has already gifted
 * the player, the exchange is mutual and {@link BefriendedPlayerMobTrigger}
 * fires instead.)
 *
 * <p>Marker trigger: carries only the standard optional {@code player}
 * predicate. Fired from
 * {@link games.brennan.dungeontrain.advancement.PlayerMobSocialTracker}
 * (driven by PlayerMob's {@code PlayerMobSocialHooks} gift seam via
 * {@code compat.PlayerMobSocialBridge}); vanilla advancement dedupe
 * keeps it to a single grant per player.</p>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:gave_playermob_unrequited" }
 * }</pre>
 */
public final class GavePlayerMobUnrequitedTrigger extends SimpleCriterionTrigger<GavePlayerMobUnrequitedTrigger.Instance> {

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
