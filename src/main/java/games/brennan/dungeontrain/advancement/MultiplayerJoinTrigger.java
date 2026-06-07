package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when the player is part of a multiplayer session — either they joined
 * a multiplayer world (a dedicated server, or any host they don't own) or a
 * guest joined the LAN world they are hosting. Marker trigger: carries only
 * the standard optional {@code player} predicate, no extra conditions, so
 * {@link #trigger(ServerPlayer)} unconditionally satisfies any instance.
 *
 * <p>Fired from {@link games.brennan.dungeontrain.event.AchievementEvents}
 * on {@link net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent};
 * vanilla advancement dedupe keeps it to a single grant per player.</p>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:multiplayer_join" }
 * }</pre>
 */
public final class MultiplayerJoinTrigger extends SimpleCriterionTrigger<MultiplayerJoinTrigger.Instance> {

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
