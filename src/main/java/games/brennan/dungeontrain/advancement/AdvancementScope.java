package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Whether an advancement belongs to the <em>player</em> or to a <em>run</em>.
 *
 * <p>Each difficulty is its own profile, but not everything should be re-earned per difficulty:</p>
 * <ul>
 *   <li><b>Cross-life</b> (global) — earned over many lives: lifetime distance, total time aboard, books
 *       read, story sets, and every one-off deed ("you did this once, ever"). That describes the player, so
 *       it belongs to them on every difficulty. Also every vanilla and other-mod advancement.</li>
 *   <li><b>Single-life</b> (per-difficulty) — must be achieved <em>within one life</em>: carriages this run,
 *       distance this run, an unbroken chest streak, a roof run, a pacifist run. Doing that on Hard is a
 *       genuinely different accomplishment from doing it on Peaceful, so each difficulty keeps its own.</li>
 * </ul>
 *
 * <p><b>Classified from the trigger</b>, not from a hand-maintained list of advancement ids: the per-run and
 * cross-world triggers already come in documented twins ({@code run_train_time} vs {@code train_time},
 * {@code run_distance} vs {@code lifetime_distance}), so a new advancement lands in the right bucket by
 * virtue of what it listens to. The trigger objects are compared by identity rather than by registry id —
 * a rename can't silently reclassify anything.</p>
 *
 * <p>The exception is the code-granted advancements, whose criterion is {@code minecraft:impossible} and so
 * says nothing about scope. Those need naming explicitly, and they genuinely split both ways: the pacifist
 * chain and the far-start pair read {@code PlayerRunState} (single-life), while the completionist capstone,
 * "Nothing But Books" and "Cover me in ...leather?" are lifetime deeds.</p>
 */
public final class AdvancementScope {

    /** Triggers fed from per-life state ({@code PlayerRunState} / single-life progress, reset on death). */
    private static final List<Supplier<? extends CriterionTrigger<?>>> PER_LIFE_TRIGGERS = List.of(
        ModAdvancementTriggers.CARTS_IN_RUN,
        ModAdvancementTriggers.CARTS_BOTH_DIRECTIONS,
        ModAdvancementTriggers.RUN_DISTANCE,
        ModAdvancementTriggers.RUN_TRAIN_TIME,
        ModAdvancementTriggers.UNIQUE_CHESTS_OPENED,
        ModAdvancementTriggers.BIOMES_VISITED,
        ModAdvancementTriggers.ROOF_RUN_GROUPS);

    /**
     * Code-granted ({@code minecraft:impossible}) advancements that are single-life. Their criterion carries
     * no scope, so the only honest option is to name them; the grant sites all read {@code PlayerRunState}.
     *
     * <p>Deliberately NOT here, because they are lifetime deeds: {@code completionist},
     * {@code nothing_but_books}, {@code leather_over_diamond}.</p>
     */
    private static final Set<ResourceLocation> PER_LIFE_IDS = Set.of(
        dt("dungeon_train/pacifist_100"),
        dt("dungeon_train/pacifist_250"),
        dt("dungeon_train/pacifist_1000"),
        dt("dungeon_train/the_far_start"),
        dt("dungeon_train/the_great_beyond"));

    private AdvancementScope() {}

    private static ResourceLocation dt(String path) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path);
    }

    /** Whether {@code holder} must be achieved within one life, and so belongs to one difficulty's profile. */
    public static boolean isPerLife(AdvancementHolder holder) {
        return isPerLife(holder, liveTriggers(), PER_LIFE_IDS);
    }

    /**
     * Pure form of {@link #isPerLife(AdvancementHolder)} — the sets are passed in so the rule can be tested
     * without a registered trigger registry.
     */
    static boolean isPerLife(AdvancementHolder holder, Set<CriterionTrigger<?>> perLifeTriggers,
                             Set<ResourceLocation> perLifeIds) {
        if (holder == null) {
            return false;
        }
        if (perLifeIds.contains(holder.id())) {
            return true;
        }
        for (Criterion<?> criterion : holder.value().criteria().values()) {
            if (perLifeTriggers.contains(criterion.trigger())) {
                return true;
            }
        }
        return false;
    }

    /** The registered per-life trigger objects. Resolved per call — the holders are only bound after registry. */
    private static Set<CriterionTrigger<?>> liveTriggers() {
        return PER_LIFE_TRIGGERS.stream()
            .map(Supplier::get)
            .collect(Collectors.toUnmodifiableSet());
    }

    /** The code-granted single-life ids, for tests and for callers that only have an id. */
    public static boolean isPerLifeId(ResourceLocation id) {
        return PER_LIFE_IDS.contains(id);
    }
}
