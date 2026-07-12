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

}
