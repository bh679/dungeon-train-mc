package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when the player has completed a two-way item exchange with a single
 * PlayerMob — the player gave it an item (it picked up the player's drop)
 * <em>and</em> it gave the player an item ({@code giveItemTo}), in either
 * order. Backs the <em>Friends</em> advancement.
 *
 * <p>Marker trigger: carries only the standard optional {@code player}
 * predicate. Fired from
 * {@link games.brennan.dungeontrain.advancement.PlayerMobSocialTracker} once
 * both halves of a (player, mob) pair are seen; vanilla advancement dedupe
 * keeps it to a single grant per player.</p>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:befriended_playermob" }
 * }</pre>
 */
public final class BefriendedPlayerMobTrigger extends SimpleCriterionTrigger<BefriendedPlayerMobTrigger.Instance> {

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
