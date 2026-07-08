package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when the player performs a discrete gameplay action while riding /
 * exploring the train. The gameplay-tab analogue of
 * {@link EditorActionTrigger}: each advancement instance carries an
 * {@code actionId} string and the matching detection site fires
 * {@link #trigger(ServerPlayer, String)} with the same id. Vanilla
 * advancement dedupe keeps each milestone to a single grant per player, so
 * these are pure one-shot markers — the detection events may fire the same
 * id every scan without harm.
 *
 * <p>These advancements can be earned in any game mode; no per-trigger
 * game-mode handling is needed here. Cross-world persistence for the
 * {@code dungeon_train/} tree is centrally gated in {@code AchievementEvents}
 * via {@link games.brennan.dungeontrain.cheat.RunIntegrity#persistsAdvancement},
 * which blocks writing to the global profile only when the run has been
 * marked "Free Play" (cheated) — it does not block the advancement firing or
 * displaying live.</p>
 *
 * <p>Action ids in current use (Dungeon Train tab):
 * <ul>
 *   <li>{@code landed_on_tracks} — stood on the rail bed below the train</li>
 *   <li>{@code left_train} — off every carriage AND off the track corridor</li>
 *   <li>{@code returned_to_train} — re-boarded after getting off the carriage</li>
 *   <li>{@code used_pillar_stairs} — entered a bridge-pillar side staircase</li>
 *   <li>{@code used_tunnel_stairs} — entered a tunnel stairwell (surface entrance or shaft)</li>
 * </ul>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:gameplay_action",
 *   "conditions": { "actionId": "landed_on_tracks" } }
 * }</pre>
 */
public final class GameplayActionTrigger extends SimpleCriterionTrigger<GameplayActionTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player, String actionId) {
        trigger(player, instance -> instance.matches(actionId));
    }

    public record Instance(Optional<ContextAwarePredicate> player, String actionId)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.STRING.fieldOf("actionId").forGetter(Instance::actionId)
        ).apply(in, Instance::new));

        public boolean matches(String firedActionId) {
            return actionId.equals(firedActionId);
        }
    }
}
