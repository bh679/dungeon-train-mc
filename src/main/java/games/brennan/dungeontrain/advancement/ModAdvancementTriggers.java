package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * Mod-side advancement-trigger registry. Registers the custom
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

    public static final Supplier<CartsBothDirectionsTrigger> CARTS_BOTH_DIRECTIONS =
        TRIGGERS.register("carts_both_directions", CartsBothDirectionsTrigger::new);

    public static final Supplier<RoofRunGroupsTrigger> ROOF_RUN_GROUPS =
        TRIGGERS.register("roof_run_groups", RoofRunGroupsTrigger::new);

    public static final Supplier<StorySetCompletedTrigger> STORY_SET_COMPLETED =
        TRIGGERS.register("story_set_completed", StorySetCompletedTrigger::new);

    public static final Supplier<EditorActionTrigger> EDITOR_ACTION =
        TRIGGERS.register("editor_action", EditorActionTrigger::new);

    public static final Supplier<GameplayActionTrigger> GAMEPLAY_ACTION =
        TRIGGERS.register("gameplay_action", GameplayActionTrigger::new);

    public static final Supplier<TrainTimeTrigger> TRAIN_TIME =
        TRIGGERS.register("train_time", TrainTimeTrigger::new);

    public static final Supplier<MultiplayerJoinTrigger> MULTIPLAYER_JOIN =
        TRIGGERS.register("multiplayer_join", MultiplayerJoinTrigger::new);

    public static final Supplier<RandomBooksReadTrigger> RANDOM_BOOKS_READ =
        TRIGGERS.register("random_books_read", RandomBooksReadTrigger::new);

    public static final Supplier<StartingBooksReadTrigger> STARTING_BOOKS_READ =
        TRIGGERS.register("starting_books_read", StartingBooksReadTrigger::new);

    // --- PlayerMob & PvP advancements ---

    public static final Supplier<GavePlayerMobUnrequitedTrigger> GAVE_PLAYERMOB_UNREQUITED =
        TRIGGERS.register("gave_playermob_unrequited", GavePlayerMobUnrequitedTrigger::new);

    public static final Supplier<BefriendedPlayerMobTrigger> BEFRIENDED_PLAYERMOB =
        TRIGGERS.register("befriended_playermob", BefriendedPlayerMobTrigger::new);

    public static final Supplier<NamedPlayerMobTrigger> NAMED_PLAYERMOB =
        TRIGGERS.register("named_playermob", NamedPlayerMobTrigger::new);

    public static final Supplier<PushedPlayerMobOffTrainTrigger> PUSHED_PLAYERMOB_OFF_TRAIN =
        TRIGGERS.register("pushed_playermob_off_train", PushedPlayerMobOffTrainTrigger::new);

    public static final Supplier<DefendedPlayerMobTrigger> DEFENDED_PLAYERMOB =
        TRIGGERS.register("defended_playermob", DefendedPlayerMobTrigger::new);

    public static final Supplier<EncounteredPlayersTrigger> ENCOUNTERED_PLAYERS =
        TRIGGERS.register("encountered_players", EncounteredPlayersTrigger::new);

    // --- Echo (reincarnation PlayerMob) advancements ---

    public static final Supplier<EncounteredEchoTrigger> ENCOUNTERED_ECHO =
        TRIGGERS.register("encountered_echo", EncounteredEchoTrigger::new);

    public static final Supplier<BefriendedEchoTrigger> BEFRIENDED_ECHO =
        TRIGGERS.register("befriended_echo", BefriendedEchoTrigger::new);

    public static final Supplier<KilledEchoTrigger> KILLED_ECHO =
        TRIGGERS.register("killed_echo", KilledEchoTrigger::new);

    private ModAdvancementTriggers() {}

    public static void register(IEventBus modBus) {
        TRIGGERS.register(modBus);
    }
}
