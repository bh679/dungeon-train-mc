package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when the player performs a discrete <em>echo</em> feat — an interaction with a PlayerMob
 * reincarnated from a fallen player (see {@link games.brennan.dungeontrain.compat.EchoIdentity}).
 * The echo analogue of {@link GameplayActionTrigger}: each advancement instance carries a
 * {@code feat} string and the detection site fires {@link #trigger(ServerPlayer, String)} with the
 * matching id. Vanilla advancement dedupe keeps each to a single grant per player, so these are
 * pure one-shot markers.
 *
 * <p>Covers the feats not already served by the dedicated own-echo markers
 * ({@code encountered_echo} / {@code befriended_echo} / {@code killed_echo}):
 * <ul>
 *   <li>{@code own_void_push} — shoved your own echo off the train to its death in the void</li>
 *   <li>{@code remote_encounter} — came within range of someone else's echo</li>
 *   <li>{@code remote_befriend} — befriended someone else's echo</li>
 *   <li>{@code remote_kill} — killed someone else's echo</li>
 *   <li>{@code remote_void_push} — shoved someone else's echo into the void</li>
 * </ul>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:echo_feat",
 *   "conditions": { "feat": "remote_encounter" } }
 * }</pre>
 */
public final class EchoFeatTrigger extends SimpleCriterionTrigger<EchoFeatTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player, String feat) {
        trigger(player, instance -> instance.matches(feat));
    }

    public record Instance(Optional<ContextAwarePredicate> player, String feat)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.STRING.fieldOf("feat").forGetter(Instance::feat)
        ).apply(in, Instance::new));

        public boolean matches(String firedFeat) {
            return feat.equals(firedFeat);
        }
    }
}
