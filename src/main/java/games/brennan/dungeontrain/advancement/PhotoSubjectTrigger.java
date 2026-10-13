package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Fires once per photo a player takes, carrying every subject key the photo shows — what was in
 * frame and where it was taken (see {@code compat.photo.PhotoSubjects} for the keys:
 * {@code entity:minecraft:cow}, {@code echo}, {@code underwater}, {@code band:reached_nether}, …).
 * A criterion matches when its {@code subject} is one of them, so a collection advancement is just
 * one criterion per subject. {@code subjects} instead asks for several keys in the <em>same</em>
 * photo — separate criteria would add up across different photos.
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:photo_subject",
 *   "conditions": { "subject": "entity:minecraft:warden" } }
 * { "trigger": "dungeontrain:photo_subject",
 *   "conditions": { "subjects": ["selfie", "entity:minecraft:warden", "entity:minecraft:wither"] } }
 * }</pre>
 */
public final class PhotoSubjectTrigger extends SimpleCriterionTrigger<PhotoSubjectTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player, Set<String> subjects) {
        if (subjects.isEmpty()) return;
        trigger(player, instance -> instance.matches(subjects));
    }

    /**
     * @param subject  one key the photo must show
     * @param subjects keys the photo must all show at once
     */
    public record Instance(Optional<ContextAwarePredicate> player, Optional<String> subject, List<String> subjects)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.<Instance>create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.STRING.optionalFieldOf("subject").forGetter(Instance::subject),
            Codec.STRING.listOf().optionalFieldOf("subjects", List.of()).forGetter(Instance::subjects)
        ).apply(in, Instance::new)).validate(Instance::validate);

        public Instance {
            subjects = List.copyOf(subjects);
        }

        private static DataResult<Instance> validate(Instance instance) {
            if (instance.subject().isEmpty() && instance.subjects().isEmpty()) {
                return DataResult.error(() -> "photo_subject needs a \"subject\" or a non-empty \"subjects\" list");
            }
            return DataResult.success(instance);
        }

        public boolean matches(Set<String> shown) {
            return subject.map(shown::contains).orElse(true) && shown.containsAll(subjects);
        }
    }
}
