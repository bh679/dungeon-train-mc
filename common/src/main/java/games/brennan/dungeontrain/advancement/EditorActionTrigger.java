package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when the player performs a discrete editor action. Each
 * advancement instance carries an {@code actionId} string; the matching
 * action's call site fires {@link #trigger(ServerPlayer, String)} with
 * the same id. Vanilla advancement dedupe keeps each milestone to a
 * single grant per player.
 *
 * <p>Action ids in current use (Editor tab):
 * <ul>
 *   <li>{@code made_carriage} — created a new full carriage template</li>
 *   <li>{@code made_carriage_part} — saved a new carriage-part template</li>
 *   <li>{@code made_contents} — created a new container-contents pool</li>
 *   <li>{@code made_contents_variant} — added a new variant to a block-variant cell</li>
 *   <li>{@code saved_package} — saved a package via {@code /package save}</li>
 *   <li>{@code exported_package} — exported user content via {@code /export}</li>
 *   <li>{@code used_block_variant} — applied a block-variant entry</li>
 *   <li>{@code used_block_variant_lock} — used the block-variant lock-id feature</li>
 *   <li>{@code used_contents_variant} — added a content-variant entry</li>
 *   <li>{@code saved_contents_variant} — persisted a contents-variant template</li>
 * </ul>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:editor_action",
 *   "conditions": { "actionId": "made_carriage" } }
 * }</pre>
 */
public final class EditorActionTrigger extends SimpleCriterionTrigger<EditorActionTrigger.Instance> {

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
