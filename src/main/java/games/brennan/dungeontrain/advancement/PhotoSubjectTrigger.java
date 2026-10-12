package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.Set;

/**
 * Fires once per photo a player takes, carrying every subject key the photo shows — what was in
 * frame and where it was taken (see {@code compat.photo.PhotoSubjects} for the keys:
 * {@code entity:minecraft:cow}, {@code echo}, {@code underwater}, {@code band:reached_nether}, …).
 * A criterion matches when its {@code subject} is one of them, so a collection advancement is just
 * one criterion per subject.
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:photo_subject",
 *   "conditions": { "subject": "entity:minecraft:warden" } }
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

    public record Instance(Optional<ContextAwarePredicate> player, String subject)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.STRING.fieldOf("subject").forGetter(Instance::subject)
        ).apply(in, Instance::new));

        public boolean matches(Set<String> subjects) {
            return subjects.contains(subject);
        }
    }
}
