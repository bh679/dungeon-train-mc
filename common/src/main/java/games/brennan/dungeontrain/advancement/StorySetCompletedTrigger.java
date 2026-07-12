package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires when a story-set is fully read. Caller passes the set id that is
 * currently complete; the criterion instance matches on string equality.
 *
 * <p>Supported set ids:
 * <ul>
 *   <li>{@code faulthurst} — every variant of every random-book whose
 *       basename contains {@code "faulthurst"} is marked seen in
 *       {@link games.brennan.dungeontrain.narrative.NarrativeProgressData}.</li>
 *   <li>{@code quiet_rules} — every variant of every random-book whose
 *       basename contains {@code "rules"} is marked seen (the "Know The Rules"
 *       achievement). Same per-world store as {@code faulthurst}.</li>
 *   <li>{@code all_stories} — every letter of every {@code StoryFile} in
 *       {@link games.brennan.dungeontrain.narrative.StoryRegistry} is
 *       marked read.</li>
 *   <li>{@code all_story_variants} — every variant of every letter of every
 *       {@code StoryFile} is marked seen. Strictly stronger than
 *       {@code all_stories}; drives the "Every Reality, Every Word"
 *       challenge advancement.</li>
 * </ul>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:story_set_completed",
 *   "conditions": { "setId": "faulthurst" } }
 * }</pre>
 */
public final class StorySetCompletedTrigger extends SimpleCriterionTrigger<StorySetCompletedTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player, String setId) {
        trigger(player, instance -> instance.matches(setId));
    }

    public record Instance(Optional<ContextAwarePredicate> player, String setId)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.STRING.fieldOf("setId").forGetter(Instance::setId)
        ).apply(in, Instance::new));

        public boolean matches(String firedSetId) {
            return setId.equals(firedSetId);
        }
    }
}
