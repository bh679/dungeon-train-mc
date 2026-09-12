package games.brennan.dungeontrain.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;

import java.util.Optional;

/**
 * A typed {@code minecraft:impossible}: never fires on its own, but carries the requirement
 * number for an advancement that Java code grants directly ({@code PacifistAdvancement},
 * {@code FarStartAdvancement}).
 *
 * <p>Those advancements used to hold their threshold as a Java constant while the datapack said
 * {@code impossible}. Moving the number here puts it where every other milestone keeps it —
 * {@code criteria.<name>.conditions.threshold} — so the relay override, the description argument
 * and the explorer's editor treat all of them alike; the granting code reads the value back via
 * {@code AdvancementRequirements}. There is deliberately no {@code trigger(...)} method: the
 * only way to earn one of these is still the code path that owns it.</p>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "trigger": "dungeontrain:code_granted",
 *   "conditions": { "threshold": 150 } }
 * }</pre>
 */
public final class CodeGrantedTrigger extends SimpleCriterionTrigger<CodeGrantedTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public record Instance(Optional<ContextAwarePredicate> player, int threshold)
        implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(in -> in.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.INT.optionalFieldOf("threshold", 1).forGetter(Instance::threshold)
        ).apply(in, Instance::new));
    }
}
