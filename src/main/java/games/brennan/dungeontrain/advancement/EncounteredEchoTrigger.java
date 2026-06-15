package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when the player comes within ~2 blocks of an <em>echo</em> — a
 * PlayerMob reincarnated from a fallen player (its skin carries a
 * {@code games.brennan.playermob.player.SourceProfileSkin} profile-ref; see
 * {@link games.brennan.dungeontrain.compat.EchoIdentity}). Roots the echo
 * advancement chain (<em>Echo Encounter</em>).
 *
 * <p>Marker trigger: carries only the standard optional {@code player}
 * predicate. Fired from
 * {@link games.brennan.dungeontrain.event.PlayerMobAdvancementEvents}'s periodic
 * proximity scan; vanilla advancement dedupe keeps it to a single grant per
 * player.</p>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:encountered_echo" }
 * }</pre>
 */
public final class EncounteredEchoTrigger extends SimpleCriterionTrigger<EncounteredEchoTrigger.Instance> {

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
