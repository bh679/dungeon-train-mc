package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.platform.DtRegistrar;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.registries.Registries;

import java.util.function.Supplier;

/**
 * Mod-side advancement-trigger registry. Registers the custom
 * {@link CriterionTrigger} implementations to vanilla's
 * {@code TRIGGER_TYPES} registry so advancement JSONs referencing
 * {@code dungeontrain:<id>} resolve correctly.
 *
 * <p>Registered via {@link DtRegistrar} (loader-neutral) instead of a direct
 * {@code DeferredRegister} — the root NeoForge module attaches the underlying
 * {@code DeferredRegister} to the mod-event bus from
 * {@link DungeonTrain}'s constructor (see
 * {@code games.brennan.dungeontrain.platform.neoforge.NeoForgeRegistrar#attachAll}),
 * at the same code position the old {@code TRIGGERS.register(modBus)} call
 * used to occupy. Calling {@link #init()} from the constructor forces this
 * class's static fields (and thus every {@code DtRegistrar.register} call
 * below) to run before that attach point.</p>
 */
public final class ModAdvancementTriggers {

    public static final Supplier<UniqueChestsOpenedTrigger> UNIQUE_CHESTS_OPENED =
        register("unique_chests_opened", UniqueChestsOpenedTrigger::new);

    public static final Supplier<CartsInRunTrigger> CARTS_IN_RUN =
        register("carts_in_run", CartsInRunTrigger::new);

    public static final Supplier<CartsBothDirectionsTrigger> CARTS_BOTH_DIRECTIONS =
        register("carts_both_directions", CartsBothDirectionsTrigger::new);

    public static final Supplier<RoofRunGroupsTrigger> ROOF_RUN_GROUPS =
        register("roof_run_groups", RoofRunGroupsTrigger::new);

    public static final Supplier<StorySetCompletedTrigger> STORY_SET_COMPLETED =
        register("story_set_completed", StorySetCompletedTrigger::new);

    public static final Supplier<EditorActionTrigger> EDITOR_ACTION =
        register("editor_action", EditorActionTrigger::new);

    public static final Supplier<GameplayActionTrigger> GAMEPLAY_ACTION =
        register("gameplay_action", GameplayActionTrigger::new);

    public static final Supplier<TrainTimeTrigger> TRAIN_TIME =
        register("train_time", TrainTimeTrigger::new);

    public static final Supplier<RunTrainTimeTrigger> RUN_TRAIN_TIME =
        register("run_train_time", RunTrainTimeTrigger::new);

    public static final Supplier<RunDistanceTrigger> RUN_DISTANCE =
        register("run_distance", RunDistanceTrigger::new);

    public static final Supplier<LifetimeDistanceTrigger> LIFETIME_DISTANCE =
        register("lifetime_distance", LifetimeDistanceTrigger::new);

    // --- Exploration / biome-diversity advancements ---

    public static final Supplier<BiomesVisitedTrigger> BIOMES_VISITED =
        register("biomes_visited", BiomesVisitedTrigger::new);

    public static final Supplier<MultiplayerJoinTrigger> MULTIPLAYER_JOIN =
        register("multiplayer_join", MultiplayerJoinTrigger::new);

    public static final Supplier<RandomBooksReadTrigger> RANDOM_BOOKS_READ =
        register("random_books_read", RandomBooksReadTrigger::new);

    public static final Supplier<StartingBooksReadTrigger> STARTING_BOOKS_READ =
        register("starting_books_read", StartingBooksReadTrigger::new);

    public static final Supplier<BooksBurnedUnreadTrigger> BOOKS_BURNED_UNREAD =
        register("books_burned_unread", BooksBurnedUnreadTrigger::new);

    // --- PlayerMob & PvP advancements ---

    public static final Supplier<GavePlayerMobUnrequitedTrigger> GAVE_PLAYERMOB_UNREQUITED =
        register("gave_playermob_unrequited", GavePlayerMobUnrequitedTrigger::new);

    public static final Supplier<BefriendedPlayerMobTrigger> BEFRIENDED_PLAYERMOB =
        register("befriended_playermob", BefriendedPlayerMobTrigger::new);

    public static final Supplier<NamedPlayerMobTrigger> NAMED_PLAYERMOB =
        register("named_playermob", NamedPlayerMobTrigger::new);

    public static final Supplier<PushedPlayerMobOffTrainTrigger> PUSHED_PLAYERMOB_OFF_TRAIN =
        register("pushed_playermob_off_train", PushedPlayerMobOffTrainTrigger::new);

    public static final Supplier<DefendedPlayerMobTrigger> DEFENDED_PLAYERMOB =
        register("defended_playermob", DefendedPlayerMobTrigger::new);

    public static final Supplier<EncounteredPlayersTrigger> ENCOUNTERED_PLAYERS =
        register("encountered_players", EncounteredPlayersTrigger::new);

    public static final Supplier<ProximityOnTrainTrigger> PROXIMITY_ON_TRAIN =
        register("proximity_on_train", ProximityOnTrainTrigger::new);

    // --- Echo (reincarnation PlayerMob) advancements ---

    public static final Supplier<EncounteredEchoTrigger> ENCOUNTERED_ECHO =
        register("encountered_echo", EncounteredEchoTrigger::new);

    public static final Supplier<BefriendedEchoTrigger> BEFRIENDED_ECHO =
        register("befriended_echo", BefriendedEchoTrigger::new);

    public static final Supplier<KilledEchoTrigger> KILLED_ECHO =
        register("killed_echo", KilledEchoTrigger::new);

    /**
     * Parameterised marker for the remaining echo feats — your own echo pushed into the void plus
     * the full someone-else's-echo set (encounter / befriend / kill / void push). See
     * {@link EchoFeatTrigger} for the {@code feat} ids.
     */
    public static final Supplier<EchoFeatTrigger> ECHO_FEAT =
        register("echo_feat", EchoFeatTrigger::new);

    private ModAdvancementTriggers() {}

    private static <I extends CriterionTrigger<?>> Supplier<I> register(String name, Supplier<I> factory) {
        return DtRegistrar.get().register(Registries.TRIGGER_TYPE, name, factory);
    }

    /** Call from the mod constructor to force this class's static fields (and their registrations) to run. */
    public static void init() {}
}
