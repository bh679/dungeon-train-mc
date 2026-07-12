package games.brennan.dungeontrain.platform.event;

/**
 * Dungeon Train's loader-neutral event surface: one {@link DtEvent} static field
 * per converted NeoForge event TYPE. Game logic (moving to {@code :common} over
 * the Fabric port) registers listeners here instead of touching NeoForge's bus;
 * a loader-specific bridge in the root module ({@code platform.neoforge.*Bridge})
 * subscribes the real NeoForge event once and fires the matching field.
 *
 * <p>Each field's Javadoc records the EXACT semantics it must preserve, matching
 * current NeoForge behavior verbatim: <b>when</b> it fires, on <b>what thread</b>,
 * whether it is <b>cancellable</b>, and whether its parameters are <b>mutable</b>.
 * Bridges are pure adapters — they add no logic and must keep those semantics
 * identical on every loader.</p>
 *
 * <p>This class is populated one category at a time (Stage 2a = server-side
 * categories, Stage 2b = client-side). Fields are added alongside their callback
 * interface, their root bridge, and the converted handler registration.</p>
 */
public final class DtEvents {

    private DtEvents() {}

    // ---- Server-side categories (Stage 2a) --------------------------------
    // Fields are added here per category, each with its exact-semantics Javadoc.

    /**
     * Command registration — NeoForge {@code RegisterCommandsEvent}. Fires once
     * per server start (integrated and dedicated) on the server thread, while
     * the command tree is built. Not cancellable. Listeners mutate the passed
     * dispatcher by registering command nodes. Bridge invokes every listener in
     * registration order.
     */
    public static final DtEvent<DtCommandRegistrationCallback> COMMAND_REGISTRATION =
        new DtEvent<>();

    /**
     * Server chat — NeoForge {@code ServerChatEvent}. Fires on the server thread
     * when a player sends a chat line, before broadcast. The NeoForge event is
     * cancellable with a mutable message, but DT's only listener is observe-only,
     * so this is a {@code void} exact-passthrough callback (see
     * {@link DtServerChatCallback}). Bridge invokes every listener in
     * registration order; it does not cancel or edit the message.
     */
    public static final DtEvent<DtServerChatCallback> SERVER_CHAT =
        new DtEvent<>();

    /**
     * Command execution — NeoForge {@code CommandEvent}. Fires on the server
     * thread after a command is parsed, before it runs. <b>Cancellable:</b>
     * listeners return {@code true} to cancel (see {@link DtCommandCallback});
     * the bridge stops on the first {@code true} and cancels the command. The
     * parse results are read-only here (DT does not rewrite them).
     */
    public static final DtEvent<DtCommandCallback> COMMAND_EXEC =
        new DtEvent<>();

    /**
     * Advancement earn — NeoForge {@code AdvancementEvent.AdvancementEarnEvent}.
     * Fires on the server thread when an entity earns an advancement (any
     * namespace), and re-fires re-entrantly when a listener grants further
     * advancements — identical to today, since the underlying grant still routes
     * through the NeoForge event. Not cancellable; read-only params. Bridge
     * invokes every listener in registration order.
     */
    public static final DtEvent<DtAdvancementEarnCallback> ADVANCEMENT_EARN =
        new DtEvent<>();

    // ---- Server lifecycle (Stage 2b) --------------------------------------

    /**
     * Server starting — NeoForge {@code ServerStartingEvent}. Fires once per
     * server start (integrated and dedicated) on the server thread, before the
     * server ticks. Not cancellable; read-only ({@code MinecraftServer}). DT
     * uses three tiers: HIGHEST ({@code UserContentMigration}), HIGH
     * ({@code UserContentImporter}, {@code DtpacksMigration}), NORMAL (rest) —
     * {@code NeoForgeLifecycleBridge} fires each tier under a matching
     * {@code @SubscribeEvent} priority.
     */
    public static final DtEvent<DtServerStartingCallback> SERVER_STARTING =
        new DtEvent<>();

    /**
     * Server started — NeoForge {@code ServerStartedEvent}. Fires once, on the
     * server thread, after the server is fully up. Not cancellable; read-only.
     * DT uses NORMAL and LOW tiers ({@code NetherBandContextEvents},
     * {@code TrainBootstrapEvents} are LOW). One handler
     * ({@code WorldLifecycleEvents}) is {@code Dist.CLIENT} — its registration is
     * gated to the physical client (see {@code NeoForgeServerEvents}).
     */
    public static final DtEvent<DtServerStartedCallback> SERVER_STARTED =
        new DtEvent<>();

    /**
     * Server stopping — NeoForge {@code ServerStoppingEvent}. Fires on the
     * server thread as shutdown begins, before worlds save/unload. Not
     * cancellable; read-only. DT uses NORMAL and LOWEST
     * ({@code ShutdownDiagnostics}) tiers.
     */
    public static final DtEvent<DtServerStoppingCallback> SERVER_STOPPING =
        new DtEvent<>();

    /**
     * Server stopped — NeoForge {@code ServerStoppedEvent}. Fires on the server
     * thread after full shutdown. Not cancellable; read-only. DT uses HIGHEST
     * ({@code ShutdownDiagnostics}) and NORMAL tiers. One handler
     * ({@code WorldLifecycleEvents}) is {@code Dist.CLIENT} — gated to the
     * physical client.
     */
    public static final DtEvent<DtServerStoppedCallback> SERVER_STOPPED =
        new DtEvent<>();

    // ---- Tick events (Stage 2b) -------------------------------------------

    /**
     * Level tick (post) — NeoForge {@code LevelTickEvent.Post}. Fires after each
     * level ticks, on BOTH client and server levels (handlers self-filter via
     * {@code instanceof ServerLevel}). Not cancellable; read-only ({@code Level}).
     * All DT handlers were NORMAL priority — {@code NeoForgeTickBridge} fires them
     * in registration order under a single NORMAL subscription.
     */
    public static final DtEvent<DtLevelTickCallback> LEVEL_TICK =
        new DtEvent<>();

    /**
     * Server tick (post) — NeoForge {@code ServerTickEvent.Post}. Fires after
     * each server tick on the server thread. Not cancellable; read-only
     * ({@code MinecraftServer}). All DT handlers NORMAL.
     */
    public static final DtEvent<DtServerTickCallback> SERVER_TICK =
        new DtEvent<>();

    /**
     * Player tick (post) — NeoForge {@code PlayerTickEvent.Post}. Fires after
     * each player ticks. Not cancellable; read-only ({@code Player}). All DT
     * handlers NORMAL.
     */
    public static final DtEvent<DtPlayerTickCallback> PLAYER_TICK =
        new DtEvent<>();

    /**
     * Entity tick (pre) — NeoForge {@code EntityTickEvent.Pre}. Fires before each
     * entity ticks. NeoForge's {@code Pre} is cancellable, but DT's only handler
     * never cancels, so this is a {@code void} passthrough. All DT handlers
     * NORMAL.
     */
    public static final DtEvent<DtEntityTickCallback> ENTITY_TICK =
        new DtEvent<>();

    // ---- Player connection (Stage 2b) -------------------------------------

    /**
     * Player login — NeoForge {@code PlayerEvent.PlayerLoggedInEvent}. Server
     * thread; not cancellable; read-only ({@code Player}). DT uses HIGHEST
     * ({@code CheatDetectionEvents} — sets Free Play) and NORMAL (rest, incl.
     * {@code AchievementEvents} which READS Free Play). The HIGHEST-before-NORMAL
     * ordering is load-bearing and is reproduced by the bridge's per-tier
     * subscriptions.
     */
    public static final DtEvent<DtPlayerLoginCallback> PLAYER_LOGIN =
        new DtEvent<>();

    /**
     * Player logout — NeoForge {@code PlayerEvent.PlayerLoggedOutEvent}. Server
     * thread; not cancellable; read-only. All DT handlers NORMAL.
     */
    public static final DtEvent<DtPlayerLogoutCallback> PLAYER_LOGOUT =
        new DtEvent<>();

    /**
     * Player respawn — NeoForge {@code PlayerEvent.PlayerRespawnEvent}. Server
     * thread; not cancellable; read-only ({@code Player} + end-portal flag). All
     * DT handlers NORMAL.
     */
    public static final DtEvent<DtPlayerRespawnCallback> PLAYER_RESPAWN =
        new DtEvent<>();

    /**
     * Player game-mode change — NeoForge {@code PlayerEvent.PlayerChangeGameModeEvent}.
     * Server thread. NeoForge's event is cancellable/mutable, but DT's sole
     * handler only observes, so this is a void read-only passthrough. NORMAL.
     */
    public static final DtEvent<DtPlayerChangeGameModeCallback> PLAYER_CHANGE_GAMEMODE =
        new DtEvent<>();

    // ---- Cancellable entity/effect events (Stage 2b) ----------------------

    /**
     * Entity join level — NeoForge {@code EntityJoinLevelEvent} (CANCELLABLE).
     * Fires as an entity is added to a level (client and server; handlers
     * self-filter). All seven DT handlers were NORMAL priority and none used
     * {@code receiveCanceled}. The callback returns {@code true} to cancel;
     * {@code NeoForgeEntityJoinBridge} stops at the first {@code true} and maps it
     * to {@code event.setCanceled(true)} — reproducing the old stop-on-cancel.
     * Only {@code NetherBandBehaviourEvents} (lightning, falling blocks) cancels.
     */
    public static final DtEvent<DtEntityJoinCallback> ENTITY_JOIN =
        new DtEvent<>();

    /**
     * Mob-effect remove — NeoForge {@code MobEffectEvent.Remove} (CANCELLABLE).
     * Server thread. DT's only handler ({@code CheatDetectionEvents}, NORMAL, no
     * {@code receiveCanceled}) cancels removal of the permanent Free Play effect.
     * Callback returns {@code true} to cancel; the bridge maps that to
     * {@code event.setCanceled(true)}.
     */
    public static final DtEvent<DtMobEffectRemoveCallback> MOB_EFFECT_REMOVE =
        new DtEvent<>();

    // ---- Living entity events (Stage 2b) ----------------------------------

    /**
     * Living death — NeoForge {@code LivingDeathEvent}. Server thread. NeoForge's
     * event is cancellable but no DT handler cancels; the callback is void and
     * carries {@code isCanceled()} read-only (one LOW handler short-circuits on
     * it). DT uses NORMAL (7) and LOW ({@code RunStatsEvents.onPlayerDeath}), none
     * with {@code receiveCanceled} — the bridge subscribes both tiers non-cancel-
     * receiving, so an other-mod cancel at higher priority skips DT exactly as
     * before.
     */
    public static final DtEvent<DtLivingDeathCallback> LIVING_DEATH =
        new DtEvent<>();

    /**
     * Living damage (post) — NeoForge {@code LivingDamageEvent.Post}. Server
     * thread, read-only (mitigation already applied). Both DT handlers NORMAL.
     */
    public static final DtEvent<DtLivingDamageCallback> LIVING_DAMAGE =
        new DtEvent<>();

    /**
     * Living equipment change — NeoForge {@code LivingEquipmentChangeEvent}.
     * Server thread; not cancellable; read-only. One DT handler, NORMAL.
     */
    public static final DtEvent<DtLivingEquipmentChangeCallback> LIVING_EQUIPMENT_CHANGE =
        new DtEvent<>();

    /**
     * Finalize spawn — NeoForge {@code FinalizeSpawnEvent}. Server thread. Not a
     * propagation-stopping cancel: its {@code setSpawnCancelled} is a result flag,
     * so every listener runs. The callback gets a {@code cancelSpawn} sink to
     * request cancellation (the bridge maps it to {@code setSpawnCancelled(true)}).
     * Both DT handlers NORMAL.
     */
    public static final DtEvent<DtFinalizeSpawnCallback> FINALIZE_SPAWN =
        new DtEvent<>();

    // ---- Chunk events (Stage 2b) ------------------------------------------

    /**
     * Chunk load — NeoForge {@code ChunkEvent.Load}. Fires when a chunk loads
     * (client and server; handlers self-filter). Not cancellable; read-only. All
     * six DT handlers NORMAL. DT has no {@code ChunkEvent.Unload} handlers.
     */
    public static final DtEvent<DtChunkLoadCallback> CHUNK_LOAD =
        new DtEvent<>();

    // ---- Block / attack / leave events (Stage 2b) -------------------------

    /**
     * Block break — NeoForge {@code BlockEvent.BreakEvent}. Server thread.
     * Cancellable upstream; no DT handler cancels (void callback carries
     * {@code isCanceled()}). All 3 DT handlers NORMAL, none {@code receiveCanceled}.
     */
    public static final DtEvent<DtBlockBreakCallback> BLOCK_BREAK =
        new DtEvent<>();

    /**
     * Single-block place — NeoForge {@code BlockEvent.EntityPlaceEvent}. Server
     * thread. Cancellable upstream; no DT handler cancels. All 3 DT handlers
     * NORMAL. NeoForge's {@code EntityMultiPlaceEvent} stays on the NeoForge bus
     * (its {@code getReplacedBlockSnapshots()} is a loader-specific type).
     */
    public static final DtEvent<DtBlockPlaceCallback> BLOCK_PLACE =
        new DtEvent<>();

    /**
     * Block drops — NeoForge {@code BlockDropsEvent}. Server thread. DT's handler
     * mutates the live drop list in place (reference passed through). NORMAL.
     */
    public static final DtEvent<DtBlockDropsCallback> BLOCK_DROPS =
        new DtEvent<>();

    /**
     * Entity leave level — NeoForge {@code EntityLeaveLevelEvent}. Fires when an
     * entity is removed (client and server; handlers self-filter). Not cancellable;
     * one DT handler, NORMAL.
     */
    public static final DtEvent<DtEntityLeaveCallback> ENTITY_LEAVE =
        new DtEvent<>();

    /**
     * Attack entity — NeoForge {@code AttackEntityEvent}. Server thread.
     * Cancellable upstream; no DT handler cancels (void callback carries
     * {@code isCanceled()}). All 4 DT handlers NORMAL.
     */
    public static final DtEvent<DtAttackEntityCallback> ATTACK_ENTITY =
        new DtEvent<>();

    // ---- Player interaction (Stage 2b, partial) ---------------------------
    // Only the non-cancelling PlayerInteractEvent sub-events are bridged. The
    // RightClickBlock sub-event stays on the NeoForge bus (cancelling handlers set
    // an InteractionResult + a HIGH-priority handler passes the event to a helper).

    /**
     * Right-click item — NeoForge {@code PlayerInteractEvent.RightClickItem}.
     * Server thread. Cancellable upstream, but DT's five handlers are pure
     * observers (never cancel), so this is a void passthrough. NORMAL.
     */
    public static final DtEvent<DtRightClickItemCallback> RIGHT_CLICK_ITEM =
        new DtEvent<>();

    /**
     * Entity interact — NeoForge {@code PlayerInteractEvent.EntityInteract}.
     * Server thread. Cancellable upstream; DT's one handler never cancels. NORMAL.
     */
    public static final DtEvent<DtEntityInteractCallback> ENTITY_INTERACT =
        new DtEvent<>();

    // =======================================================================
    //  CLIENT-side categories (Stage 2c) — registered ONLY on the physical
    //  client (see NeoForgeClientEvents, gated by DtPlatform.isClient()). Their
    //  bridges are @EventBusSubscriber(value = Dist.CLIENT) so the classes never
    //  load on a dedicated server. Register-style (mod-bus) events are converted
    //  to declarative tables, mirroring COMMAND_REGISTRATION.
    // =======================================================================

    // ---- Client tick (Stage 2c) -------------------------------------------

    /**
     * Client tick (post) — NeoForge {@code ClientTickEvent.Post}. Fires after each
     * client tick on the render/client thread. Not cancellable; DT's 26 handlers
     * ignore the event object (all read {@code Minecraft.getInstance()}). All
     * NORMAL priority — {@code NeoForgeClientTickBridge} fires them in registration
     * order under a single subscription.
     */
    public static final DtEvent<DtClientTickCallback> CLIENT_TICK_POST =
        new DtEvent<>();

    /**
     * Client tick (pre) — NeoForge {@code ClientTickEvent.Pre}. Fires before each
     * client tick on the render/client thread. NeoForge's {@code Pre} is
     * cancellable, but DT's sole handler ({@code CinematicInputHandler}) never
     * cancels, so this is a {@code void} passthrough. NORMAL.
     */
    public static final DtEvent<DtClientTickCallback> CLIENT_TICK_PRE =
        new DtEvent<>();

    // ---- Client connection (Stage 2c) -------------------------------------

    /**
     * Client logging in — NeoForge {@code ClientPlayerNetworkEvent.LoggingIn}.
     * Fires on the client thread when the local player joins a server (integrated
     * or dedicated). Not cancellable; read-only, and DT's 3 handlers ignore the
     * event object. All NORMAL — {@code NeoForgeClientConnectionBridge} fires them
     * in registration order.
     */
    public static final DtEvent<DtClientLoggingCallback> CLIENT_LOGGING_IN =
        new DtEvent<>();

    /**
     * Client logging out — NeoForge {@code ClientPlayerNetworkEvent.LoggingOut}.
     * Fires on the client thread when the local player disconnects. Not cancellable;
     * DT's 15 handlers ignore the event object (they reset client-side caches). All
     * NORMAL.
     */
    public static final DtEvent<DtClientLoggingCallback> CLIENT_LOGGING_OUT =
        new DtEvent<>();

    // ---- Register-style client events (Stage 2c) --------------------------
    // Converted to declarative registration, mirroring COMMAND_REGISTRATION:
    // the root bridge iterates the listeners into the real NeoForge event, and a
    // Fabric bridge can iterate the same tables into Fabric's registration APIs.

    /**
     * Client-command registration — NeoForge {@code RegisterClientCommandsEvent}
     * (game bus, client only). Fires on the client thread while the client-command
     * tree is built. Not cancellable; the dispatcher is mutated by registering
     * nodes. One handler ({@code NewWorldCommand}).
     */
    public static final DtEvent<DtClientCommandRegistrationCallback> CLIENT_COMMAND_REGISTRATION =
        new DtEvent<>();

    /**
     * Key-mapping registration — NeoForge mod-bus {@code RegisterKeyMappingsEvent}.
     * Fires once on the client during setup. Not cancellable; each listener passes
     * its {@code KeyMapping}(s) to the supplied registrar sink. Six handlers, all
     * independent (order irrelevant).
     */
    public static final DtEvent<DtKeyMappingRegistrationCallback> KEY_MAPPING_REGISTRATION =
        new DtEvent<>();

    /**
     * GUI-layer registration — NeoForge mod-bus {@code RegisterGuiLayersEvent}.
     * Fires once on the client during setup. Not cancellable; each listener records
     * its HUD overlay layer + anchor via the {@link DtGuiLayerRegistrar}. Four
     * handlers, all {@code registerAboveAll} (order among them irrelevant).
     */
    public static final DtEvent<DtGuiLayerRegistrationCallback> GUI_LAYER_REGISTRATION =
        new DtEvent<>();

    /**
     * Client tooltip-component factory registration — NeoForge mod-bus
     * {@code RegisterClientTooltipComponentFactoriesEvent}. Fires once on the client
     * during setup. Not cancellable; each listener records a data-type → factory
     * mapping via the {@link DtClientTooltipFactoryRegistrar}. One handler.
     */
    public static final DtEvent<DtClientTooltipComponentFactoryRegistrationCallback> CLIENT_TOOLTIP_FACTORY_REGISTRATION =
        new DtEvent<>();

    /**
     * Creative-tab contents registration — NeoForge mod-bus
     * {@code BuildCreativeModeTabContentsEvent}. Fires once PER registered
     * {@code CreativeModeTab} (vanilla and mod tabs alike), on both logical
     * sides (harmless server-side — no display effect there; kept unchanged
     * from the original {@code @EventBusSubscriber(modid=...)} handlers, which
     * were NOT {@code Dist.CLIENT}-gated). Not cancellable; each listener
     * checks the tab key and, if it matches, feeds stacks to the output sink.
     * Two handlers ({@code ModItems}, {@code ModBlocks}), independent (order
     * irrelevant).
     */
    public static final DtEvent<DtBuildCreativeTabContentsCallback> BUILD_CREATIVE_TAB_CONTENTS =
        new DtEvent<>();

    // ---- Client tooltip / render / observation (Stage 2c) -----------------

    /**
     * Item tooltip — NeoForge {@code ItemTooltipEvent} (client game bus). Not
     * treated as cancellable by DT; the handler mutates the live tooltip line list.
     * One handler ({@code PrefabUseHandler.onTooltip}), NORMAL.
     */
    public static final DtEvent<DtItemTooltipCallback> ITEM_TOOLTIP =
        new DtEvent<>();

    /**
     * Effect tooltip — NeoForge {@code GatherEffectScreenTooltipsEvent} (client game
     * bus). Not cancellable; the handler mutates the live tooltip line list. Two
     * handlers ({@code WarmthOfTheFireTooltip}, {@code FreePlayTooltip}), NORMAL.
     */
    public static final DtEvent<DtEffectTooltipCallback> EFFECT_TOOLTIP =
        new DtEvent<>();

    /**
     * Tooltip components — NeoForge {@code RenderTooltipEvent.GatherComponents}
     * (client game bus). Not cancellable; the handler appends a custom tooltip
     * component to the live element list. One handler
     * ({@code PrefabTooltipEvents.ForgeBus}), NORMAL.
     */
    public static final DtEvent<DtGatherTooltipComponentsCallback> GATHER_TOOLTIP_COMPONENTS =
        new DtEvent<>();

    /**
     * Fog colour — NeoForge {@code ViewportEvent.ComputeFogColor} (client game bus,
     * render thread). Not cancellable; the handler mutates the fog RGB via
     * {@link DtFogColor}. Three handlers ({@code VoidSkyEvents}, {@code NetherFogEvents},
     * {@code UpsideDownFogEvents}), NORMAL.
     */
    public static final DtEvent<DtFogColorCallback> FOG_COLOR =
        new DtEvent<>();

    /**
     * Music selection — NeoForge {@code SelectMusicEvent} (client game bus). Not
     * cancelled by DT; the handler overrides the track via {@link DtMusicSelection}.
     * Two handlers ({@code VoidSkyEvents}, {@code NetherFogEvents}), NORMAL.
     */
    public static final DtEvent<DtSelectMusicCallback> SELECT_MUSIC =
        new DtEvent<>();

    /**
     * Render hand — NeoForge {@code RenderHandEvent} (client game bus, render
     * thread). CANCELLABLE: the callback returns {@code true} to suppress the
     * first-person hand render; {@code NeoForgeClientRenderBridge} stops on the first
     * {@code true} and maps it to {@code event.setCanceled(true)}. One handler
     * ({@code CinematicInputHandler}), NORMAL.
     */
    public static final DtEvent<DtRenderHandCallback> RENDER_HAND =
        new DtEvent<>();

    /**
     * Client chat — NeoForge {@code ClientChatEvent} (client game bus). Cancellable /
     * mutable upstream, but DT's sole handler ({@code DevMessageConsentClient}) only
     * observes, so this is a {@code void} passthrough that never cancels or edits the
     * message. NORMAL.
     */
    public static final DtEvent<DtClientChatCallback> CLIENT_CHAT =
        new DtEvent<>();

    // ---- Client input (cancellable) (Stage 2c) ----------------------------

    /**
     * Mouse button (pre) — NeoForge {@code InputEvent.MouseButton.Pre} (client game
     * bus). Fires before screen routing; handlers self-guard on
     * {@code Minecraft.getInstance().screen}. CANCELLABLE: callback returns
     * {@code true} to swallow the button; the bridge stops on the first {@code true}.
     * DT registers the nine menu observers first and the one canceller
     * ({@code CinematicInputHandler}) last, so observers always run — semantically a
     * safe superset of the old (scan-order-independent) behaviour. All NORMAL.
     */
    public static final DtEvent<DtMouseButtonCallback> MOUSE_BUTTON_PRE =
        new DtEvent<>();

    /**
     * Interaction key-mapping — NeoForge {@code InputEvent.InteractionKeyMappingTriggered}
     * (client game bus). CANCELLABLE + mutable ({@code setSwingHand}). DT's nine menu
     * handlers cancel + suppress swing via {@link DtInteractionInput}; two hotkey
     * handlers only observe. Observers are registered first so they always run; the
     * bridge then skips any handler once the event is canceled (matching NeoForge's
     * non-{@code receiveCanceled} dispatch). All NORMAL.
     */
    public static final DtEvent<DtInteractionInputCallback> INTERACTION_KEY =
        new DtEvent<>();

    /**
     * Keyboard key (raw) — NeoForge {@code InputEvent.Key} (client game bus). Fires
     * on a raw key transition before screen routing; handlers self-guard on
     * {@code Minecraft.getInstance().screen}. NOT cancellable — {@code void}
     * passthrough. Two handlers ({@code CinematicInputHandler} — Space skips the
     * intro / any key reveals the hint; {@code CommandMenuInputHandler} — feeds the
     * typing buffer while the worldspace menu is in typing mode). Both NORMAL,
     * independent (mutually-exclusive active conditions), order irrelevant.
     */
    public static final DtEvent<DtKeyInputCallback> KEY_INPUT =
        new DtEvent<>();

    /**
     * Mouse scroll — NeoForge {@code InputEvent.MouseScrollingEvent} (client game
     * bus). Fires before screen routing; the sole handler
     * ({@code CinematicInputHandler}) self-guards on {@code screen}. CANCELLABLE: the
     * callback returns {@code true} to swallow the scroll (suppress zoom / hotbar
     * cycle during the cinematic); the bridge stops on the first {@code true}. NORMAL.
     */
    public static final DtEvent<DtMouseScrollCallback> MOUSE_SCROLL =
        new DtEvent<>();

    /**
     * Left-click block — NeoForge {@code PlayerInteractEvent.LeftClickBlock}
     * (client-side uses). CANCELLABLE. Two tiers: HIGHEST (nine menu handlers that
     * cancel) and NORMAL (one observer, {@code RideSnapshotDirector}). The bridge
     * fires each tier under a matching {@code @SubscribeEvent} priority so a HIGHEST
     * cancel skips the NORMAL observer exactly as on the bus.
     */
    public static final DtEvent<DtLeftClickBlockCallback> LEFT_CLICK_BLOCK =
        new DtEvent<>();

    /**
     * Right-click block — NeoForge {@code PlayerInteractEvent.RightClickBlock}.
     * CANCELLABLE with a result (see {@link DtRightClickBlock}). Three tiers:
     * HIGHEST ({@code CommandMenuInputHandler}), HIGH ({@code PrefabUseHandler}) and
     * NORMAL (lectern / narrative / achievement / variant). {@code NeoForgeRightClickBlockBridge}
     * fires each tier under a matching {@code @SubscribeEvent} priority and skips any
     * handler once canceled, so a higher-tier consume suppresses the rest exactly as
     * the former separate {@code @SubscribeEvent}s did.
     */
    public static final DtEvent<DtRightClickBlockCallback> RIGHT_CLICK_BLOCK =
        new DtEvent<>();

    // ---- Screen events (Stage 2c) -----------------------------------------

    /**
     * Screen opening — NeoForge {@code ScreenEvent.Opening} (client game bus).
     * CANCELLABLE + mutable ({@code setNewScreen}). Four handlers: one observer
     * ({@code BookReadClientEvents}), one canceller ({@code CinematicInputHandler}),
     * two screen-replacers ({@code CinematicPreloadGate}, {@code DeathScreenLayoutHandler}).
     * The bridge skips any handler once the event is canceled (NeoForge's
     * non-{@code receiveCanceled} dispatch). All NORMAL.
     */
    public static final DtEvent<DtScreenOpeningCallback> SCREEN_OPENING =
        new DtEvent<>();

    /**
     * Screen init (post) — NeoForge {@code ScreenEvent.Init.Post} (client game bus).
     * Not cancellable; handlers add widgets to the screen via {@link DtScreenInit}.
     * Five handlers, NORMAL.
     */
    public static final DtEvent<DtScreenInitCallback> SCREEN_INIT_POST =
        new DtEvent<>();

    /**
     * Screen render (pre) — NeoForge {@code ScreenEvent.Render.Pre} (client game bus,
     * render thread). Cancellable upstream but no DT handler cancels — void
     * passthrough of the screen. Four handlers, NORMAL.
     */
    public static final DtEvent<DtScreenRenderPreCallback> SCREEN_RENDER_PRE =
        new DtEvent<>();

    /**
     * Screen render (post) — NeoForge {@code ScreenEvent.Render.Post} (client game
     * bus, render thread). Not cancellable; the handler draws an overlay. One handler
     * ({@code MenuChatButtonHandler}), NORMAL.
     */
    public static final DtEvent<DtScreenRenderPostCallback> SCREEN_RENDER_POST =
        new DtEvent<>();

    /**
     * Screen closing — NeoForge {@code ScreenEvent.Closing} (client game bus). Not
     * cancellable; handlers reset client state. Four handlers, NORMAL.
     */
    public static final DtEvent<DtScreenClosingCallback> SCREEN_CLOSING =
        new DtEvent<>();

    // ---- World render (Stage 2c) ------------------------------------------

    /**
     * Level render at {@code AFTER_TRANSLUCENT_BLOCKS} — NeoForge
     * {@code RenderLevelStageEvent} filtered to that one stage (client render
     * thread). All 12 DT handlers gated on exactly this stage, so
     * {@code NeoForgeClientRenderLevelBridge} performs the stage check and fires this
     * field only then (the field name encodes the stage, matching Fabric's per-stage
     * {@code WorldRenderEvents.AFTER_TRANSLUCENT} callback). Not cancellable; handlers
     * draw world-space overlays. All NORMAL; order irrelevant.
     */
    public static final DtEvent<DtRenderLevelAfterTranslucentCallback> RENDER_LEVEL_AFTER_TRANSLUCENT =
        new DtEvent<>();

}
