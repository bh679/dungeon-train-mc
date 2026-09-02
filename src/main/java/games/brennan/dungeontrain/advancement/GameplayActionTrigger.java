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
 *   <li>{@code entered_portal_room} — stood in a portal room's body, past its corridor, for 5s</li>
 *   <li>{@code distant_portal_exit} — left a portal room by a corridor far from the way in</li>
 *   <li>{@code ate_edible_backpack} — ate an edible backpack (either size)</li>
 *   <li>{@code crafted_upgraded_backpack} — crafted the 3×3 compressed backpack</li>
 *   <li>{@code maxed_backpack_slots} — unlocked every backpack slot the config allows</li>
 *   <li>{@code no_container_100_carts} / {@code no_container_1000_carts} — carriages
 *       travelled since the last chest/barrel open (decorated pots don't count)</li>
 *   <li>{@code no_break_100_carts} / {@code no_break_1000_carts} — carriages
 *       travelled since the last block broken (decorated pots DO count)</li>
 *   <li>{@code contained_loop} — died 1000+ carriages into a life that never opened an
 *       ender chest (fired from the death hook, not a live scan)</li>
 *   <li>{@code changed_engine_volume} — changed the train engine volume setting; the only
 *       id fired from a client report ({@code ClientActionPacket}) rather than server detection</li>
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
