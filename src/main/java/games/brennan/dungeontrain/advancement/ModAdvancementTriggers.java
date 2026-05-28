package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * Mod-side advancement-trigger registry. Registers the three custom
 * {@link CriterionTrigger} implementations to vanilla's
 * {@code TRIGGER_TYPES} registry so advancement JSONs referencing
 * {@code dungeontrain:<id>} resolve correctly.
 *
 * <p>Registered from {@link DungeonTrain}'s constructor on the mod-event
 * bus, mirroring {@link games.brennan.dungeontrain.registry.ModItems#register}.</p>
 */
public final class ModAdvancementTriggers {

    public static final DeferredRegister<CriterionTrigger<?>> TRIGGERS =
        DeferredRegister.create(Registries.TRIGGER_TYPE, DungeonTrain.MOD_ID);

    public static final Supplier<UniqueChestsOpenedTrigger> UNIQUE_CHESTS_OPENED =
        TRIGGERS.register("unique_chests_opened", UniqueChestsOpenedTrigger::new);

    public static final Supplier<CartsInRunTrigger> CARTS_IN_RUN =
        TRIGGERS.register("carts_in_run", CartsInRunTrigger::new);

    public static final Supplier<StorySetCompletedTrigger> STORY_SET_COMPLETED =
        TRIGGERS.register("story_set_completed", StorySetCompletedTrigger::new);

    public static final Supplier<EditorActionTrigger> EDITOR_ACTION =
        TRIGGERS.register("editor_action", EditorActionTrigger::new);

    public static final Supplier<TrainTimeTrigger> TRAIN_TIME =
        TRIGGERS.register("train_time", TrainTimeTrigger::new);

    public static final Supplier<RandomBooksReadTrigger> RANDOM_BOOKS_READ =
        TRIGGERS.register("random_books_read", RandomBooksReadTrigger::new);

    public static final Supplier<StartingBooksReadTrigger> STARTING_BOOKS_READ =
        TRIGGERS.register("starting_books_read", StartingBooksReadTrigger::new);

    private ModAdvancementTriggers() {}

    public static void register(IEventBus modBus) {
        TRIGGERS.register(modBus);
    }
}
