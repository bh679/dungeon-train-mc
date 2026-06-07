package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires the first time a PlayerMob gifts the player an item — the "wordless
 * greeting" of a FRIENDLY passenger (the bundled playermob mod's
 * {@code FriendlyGreetGoal} → {@code giveItemTo}). Backs the
 * <em>A Silent Friend</em> advancement.
 *
 * <p>Marker trigger: carries only the standard optional {@code player}
 * predicate. Fired from
 * {@link games.brennan.dungeontrain.advancement.PlayerMobSocialTracker}
 * (driven by {@code mixin.PlayerMobGiveItemMixin}); vanilla advancement
 * dedupe keeps it to a single grant per player.</p>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:received_playermob_gift" }
 * }</pre>
 */
public final class ReceivedPlayerMobGiftTrigger extends SimpleCriterionTrigger<ReceivedPlayerMobGiftTrigger.Instance> {

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
