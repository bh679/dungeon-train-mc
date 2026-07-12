package games.brennan.dungeontrain.fabric.event;

/**
 * Root-side registrar that wires Dungeon Train's converted, still-root-resident
 * server-side handlers to their {@code DtEvents} fields. Invoked once from the
 * {@code DungeonTrain} constructor, immediately after {@code DungeonTrainCommon.init()}
 * and before any {@code NeoForge*Bridge} could fire an event.
 *
 * <p>This exists only because Stage 2a deliberately leaves the handler classes
 * in the root module (files are not moved to {@code :common} this run), so
 * {@code :common}'s {@code DungeonTrainCommon.init()} cannot reference them. As
 * each handler class migrates into {@code :common} in a later fixpoint, its
 * registration line moves from here into {@code DungeonTrainCommon.init()} and
 * this class shrinks toward empty.</p>
 *
 * <p><b>Ordering contract:</b> registrations here run in a deterministic order.
 * NeoForge fired same-priority same-bus handlers in annotation-scan order (not
 * something DT logic relied on across independent handlers); where DT DID rely
 * on {@code EventPriority}, handlers are registered high-priority-first so the
 * invocation order the bridge produces matches the old bus order. Each category
 * block below documents its ordering when it matters.</p>
 */
public final class FabricServerEvents {

    private FabricServerEvents() {}

    private static final org.slf4j.Logger DT_LOG = com.mojang.logging.LogUtils.getLogger();

    /**
     * Register a handler, skipping it if its class can't load. On Fabric-without-siblings a
     * sibling-coupled handler class (playermob / discordpresence / AIN / AIS / ECP) throws a
     * LinkageError when its method reference is materialised; that feature is then inert (Fabric-v1
     * gap) while the rest of the core loop registers normally. On NeoForge every class loads, so
     * nothing is ever skipped.
     */
    private static void safe(Runnable registration) {
        try {
            registration.run();
        } catch (Throwable e) {
            DT_LOG.debug("Skipped a sibling-coupled DT handler on this loader: {}", e.toString());
        }
    }

    /** Register every converted server-side handler with its {@code DtEvents} field. */
    public static void register() {
        // Category registrations are added here, one block per converted category.

        // --- Command registration (RegisterCommandsEvent) --------------------
        // Single handler; order irrelevant.
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.COMMAND_REGISTRATION
            .register(games.brennan.dungeontrain.event.CommandEvents::onRegisterCommands));

        // --- Server chat (ServerChatEvent) -----------------------------------
        // Single (observe-only) handler; order irrelevant.
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_CHAT
            .register(games.brennan.dungeontrain.event.MentionPresenceEvents::onServerChat));

        // --- Command execution (CommandEvent, cancellable) -------------------
        // Single handler; the Free Play gate cancels a tainting command.
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.COMMAND_EXEC
            .register(games.brennan.dungeontrain.event.CheatDetectionEvents::onCommand));

        // --- Advancement earn (AdvancementEarnEvent) -------------------------
        // Single handler; order irrelevant.
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.ADVANCEMENT_EARN
            .register(games.brennan.dungeontrain.event.AchievementEvents::onAdvancementEarn));

        registerServerLifecycle();
        registerTicks();
        registerPlayerConnection();
        registerCancellable();
        registerLiving();
        registerChunk();
        registerBlockAndAttack();
        registerInteract();
        registerCreativeTabContents();
        registerReloadListeners();
        registerBlockEntityTypes();
    }

    /**
     * Server-data reload listeners (AddReloadListenerEvent, game bus), fired by
     * {@code NeoForgeReloadListenerBridge}. One handler ({@code NarrativeDataLoaders})
     * registers the four narrative-prose registries onto the datapack pipeline.
     */
    private static void registerReloadListeners() {
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_RELOAD_LISTENER_REGISTRATION
            .register(games.brennan.dungeontrain.narrative.NarrativeDataLoaders::registerReloadListeners));
    }

    /**
     * Block-entity-type valid-block extensions (BlockEntityTypeAddBlocksEvent, mod-bus,
     * both sides), fired by {@code NeoForgeBlockEntityBridge}. One handler
     * ({@code NarrativeLecternHooks}) binds the narrative lectern to the vanilla lectern BE.
     */
    private static void registerBlockEntityTypes() {
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.BLOCK_ENTITY_TYPE_ADD_BLOCKS
            .register(games.brennan.dungeontrain.narrative.block.NarrativeLecternHooks::onAddBlocks));
    }

    /**
     * Creative-tab contents (BuildCreativeModeTabContentsEvent, mod-bus). Two
     * root-resident handlers ({@code ModItems}, {@code ModBlocks} — converted
     * to {@code DtRegistrar} but not yet moved to {@code :common}); order
     * irrelevant.
     */
    private static void registerCreativeTabContents() {
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.BUILD_CREATIVE_TAB_CONTENTS
            .register(games.brennan.dungeontrain.registry.ModItems::onBuildCreativeTabs));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.BUILD_CREATIVE_TAB_CONTENTS
            .register(games.brennan.dungeontrain.registry.ModBlocks::onBuildCreativeTabs));
    }

    /**
     * Non-cancelling {@code PlayerInteractEvent} sub-events (RightClickItem,
     * EntityInteract), fired by {@code NeoForgeInteractBridge}. All NORMAL
     * observers that never cancel.
     *
     * <p><b>RightClickBlock is NOT converted</b> and stays on the NeoForge bus:
     * {@code PrefabUseHandler.onRightClickBlock} (HIGH) and
     * {@code LetterLecternEvents.onRightClickBlock} (NORMAL) cancel with a
     * {@code setCancellationResult(InteractionResult)}, and {@code PrefabUseHandler}
     * passes the event object into a helper — none of which a thin loader-neutral
     * callback can carry without abstracting {@code InteractionResult} and the event
     * itself. Left for a later fixpoint. {@code VariantBlockInteractions},
     * {@code AchievementEvents.onRightClickBlock} and
     * {@code NarrativeBookEvents.onRightClickBlock} therefore also remain
     * bus-subscribed (their stop-on-cancel interleaving with the cancellers must be
     * preserved together).</p>
     */
    private static void registerInteract() {
        // PlayerInteractEvent.RightClickItem
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.RIGHT_CLICK_ITEM
            .register(games.brennan.dungeontrain.narrative.NarrativeBookEvents::onRightClickItem));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.RIGHT_CLICK_ITEM
            .register(games.brennan.dungeontrain.narrative.NarrativeBookEvents::onRightClickRandomBookItem));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.RIGHT_CLICK_ITEM
            .register(games.brennan.dungeontrain.narrative.NarrativeBookEvents::onRightClickStartingBookItem));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.RIGHT_CLICK_ITEM
            .register(games.brennan.dungeontrain.narrative.NarrativeBookEvents::onRightClickFoundSharedBookItem));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.RIGHT_CLICK_ITEM
            .register(games.brennan.dungeontrain.event.RunStatsEvents::onBookRead));

        // PlayerInteractEvent.EntityInteract
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.ENTITY_INTERACT
            .register(games.brennan.dungeontrain.event.PlayerMobAdvancementEvents::onEntityInteract));

        // PlayerInteractEvent.RightClickBlock (CANCELLABLE, fired by NeoForgeRightClickBlockBridge).
        // Tiers preserve the former @SubscribeEvent priorities: PrefabUseHandler was HIGH; the
        // lectern / narrative / achievement / variant handlers were all NORMAL (independent,
        // mutually-exclusive block conditions — same-tier order is irrelevant). The HIGHEST tier
        // (CommandMenuInputHandler, client-only) is registered from NeoForgeClientEvents so the
        // client class never loads on a dedicated server.
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.RIGHT_CLICK_BLOCK
            .register(games.brennan.dungeontrain.platform.event.DtPriority.HIGH,
                games.brennan.dungeontrain.event.PrefabUseHandler::onRightClickBlock));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.RIGHT_CLICK_BLOCK
            .register(games.brennan.dungeontrain.narrative.LetterLecternEvents::onRightClickBlock));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.RIGHT_CLICK_BLOCK
            .register(games.brennan.dungeontrain.narrative.NarrativeBookEvents::onRightClickBlock));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.RIGHT_CLICK_BLOCK
            .register(games.brennan.dungeontrain.event.AchievementEvents::onRightClickBlock));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.RIGHT_CLICK_BLOCK
            .register(games.brennan.dungeontrain.editor.VariantBlockInteractions::onRightClickBlock));
    }

    /**
     * Block break/place/drops, entity-leave and attack-entity handlers, fired by
     * {@code NeoForgeBlockBridge}. All were NORMAL priority and none cancel; order
     * within a tier is irrelevant. NeoForge's {@code EntityMultiPlaceEvent} keeps
     * its own {@code @SubscribeEvent} on {@code EditorMirrorLiveHandler} (its
     * snapshot list is a loader-specific type — left on the NeoForge bus).
     */
    private static void registerBlockAndAttack() {
        // BlockEvent.BreakEvent
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.BLOCK_BREAK
            .register(games.brennan.dungeontrain.event.RunStatsEvents::onPotBreak));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.BLOCK_BREAK
            .register(games.brennan.dungeontrain.editor.VariantBlockBreakHandler::onBlockBreak));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.BLOCK_BREAK
            .register(games.brennan.dungeontrain.editor.EditorMirrorLiveHandler::onBlockBreak));

        // BlockEvent.EntityPlaceEvent (single-block place)
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.BLOCK_PLACE
            .register(games.brennan.dungeontrain.event.PrefabUseHandler::onEntityPlace));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.BLOCK_PLACE
            .register(games.brennan.dungeontrain.event.NetherBandBehaviourEvents::onBlockPlace));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.BLOCK_PLACE
            .register(games.brennan.dungeontrain.editor.EditorMirrorLiveHandler::onBlockPlace));

        // BlockDropsEvent
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.BLOCK_DROPS
            .register(games.brennan.dungeontrain.narrative.RandomBookDropEvents::onBlockDrops));

        // EntityLeaveLevelEvent
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.ENTITY_LEAVE
            .register(games.brennan.dungeontrain.event.ContentsEntityDiagnostics::onEntityLeave));

        // AttackEntityEvent
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.ATTACK_ENTITY
            .register(games.brennan.dungeontrain.echo.EchoEncounterEvents::onAttackEntity));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.ATTACK_ENTITY
            .register(games.brennan.dungeontrain.event.PlayerMobAdvancementEvents::onAttackEntity));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.ATTACK_ENTITY
            .register(games.brennan.dungeontrain.editor.VariantEntityBreakHandler::onAttackEntity));
    }

    /**
     * Chunk-load handlers, fired by {@code NeoForgeChunkBridge}. All six were
     * NORMAL priority, not cancellable, not dist-gated; order within the tier is
     * irrelevant.
     */
    private static void registerChunk() {
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.CHUNK_LOAD
            .register(games.brennan.dungeontrain.event.NetherTransitionEvents::onChunkLoad));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.CHUNK_LOAD
            .register(games.brennan.dungeontrain.event.TrackChunkEvents::onChunkLoad));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.CHUNK_LOAD
            .register(games.brennan.dungeontrain.event.WorldDisintegrationEvents::onChunkLoad));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.CHUNK_LOAD
            .register(games.brennan.dungeontrain.event.BedrockFloorEvents::onChunkLoad));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.CHUNK_LOAD
            .register(games.brennan.dungeontrain.event.CorridorCleanupEvents::onChunkLoad));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.CHUNK_LOAD
            .register(games.brennan.dungeontrain.event.WorldUpsideDownEvents::onChunkLoad));

        // LevelEvent.Unload — one handler (drops the overworld mirror-plan cache).
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_UNLOAD
            .register(games.brennan.dungeontrain.event.WorldUpsideDownEvents::onLevelUnload));
    }

    /**
     * Living-entity handlers (death, post-damage, equipment change), fired by
     * {@code NeoForgeLivingBridge}. Death is registered with an explicit LOW tier
     * for {@code RunStatsEvents.onPlayerDeath} (its former priority) and NORMAL for
     * the other seven; the bridge fires each tier under a matching priority. No DT
     * death handler cancels or uses {@code receiveCanceled}. Damage (Post, read-
     * only) and equipment change were single-tier NORMAL.
     */
    private static void registerLiving() {
        var LOW = games.brennan.dungeontrain.platform.event.DtPriority.LOW;

        // LivingDeathEvent — NORMAL (7) + LOW (RunStats.onPlayerDeath)
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LIVING_DEATH
            .register(games.brennan.dungeontrain.echo.EchoEncounterEvents::onDeath));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LIVING_DEATH
            .register(games.brennan.dungeontrain.event.RunStatsEvents::onMobKilled));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LIVING_DEATH
            .register(games.brennan.dungeontrain.event.DeathNoteEvents::onPlayerDeath));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LIVING_DEATH
            .register(games.brennan.dungeontrain.event.DeathNoteEvents::onEchoDeath));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LIVING_DEATH
            .register(games.brennan.dungeontrain.event.PlayerMobAdvancementEvents::onMobKilled));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LIVING_DEATH
            .register(games.brennan.dungeontrain.event.PlayerMobAdvancementEvents::onEchoKilled));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LIVING_DEATH
            .register(games.brennan.dungeontrain.event.PlayerMobAdvancementEvents::onEchoFellToVoid));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LIVING_DEATH
            .register(LOW, games.brennan.dungeontrain.event.RunStatsEvents::onPlayerDeath));

        // LivingDamageEvent.Post — NORMAL
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LIVING_DAMAGE
            .register(games.brennan.dungeontrain.echo.EchoEncounterEvents::onLivingDamage));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LIVING_DAMAGE
            .register(games.brennan.dungeontrain.event.RunStatsEvents::onLivingDamage));

        // LivingEquipmentChangeEvent — NORMAL
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LIVING_EQUIPMENT_CHANGE
            .register(games.brennan.dungeontrain.narrative.NarrativeBookEvents::onEquipmentChange));

        // FinalizeSpawnEvent — NORMAL (both always run; cancelSpawn sink)
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.FINALIZE_SPAWN
            .register(games.brennan.dungeontrain.event.BandMobSpawnEvents::onFinalizeSpawn));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.FINALIZE_SPAWN
            .register(games.brennan.dungeontrain.event.NetherMobSpawner::onFinalizeSpawn));
    }

    /**
     * Cancellable events: {@code EntityJoinLevelEvent} and
     * {@code MobEffectEvent.Remove}, fired by {@code NeoForgeEntityJoinBridge}.
     * All handlers were NORMAL priority and none used {@code receiveCanceled}, so
     * the bridge stops at the first callback that returns {@code true}. The five
     * non-cancelling entity-join handlers keep {@code void} bodies and are wrapped
     * in a {@code return false} adapter here (only {@code NetherBandBehaviourEvents}
     * actually returns a cancel decision). Cancellers are registered last so the
     * observers always run — semantically identical since a cancelled join discards
     * the entity the observers would ignore anyway.
     */
    private static void registerCancellable() {
        // EntityJoinLevelEvent — non-cancelling observers (void → return false adapters)
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.ENTITY_JOIN
            .register((e, l, d) -> { games.brennan.dungeontrain.event.ContentsEntityDiagnostics.onEntityJoin(e, l, d); return false; }));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.ENTITY_JOIN
            .register((e, l, d) -> { games.brennan.dungeontrain.event.MobDifficultyEvents.onEntityJoin(e, l, d); return false; }));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.ENTITY_JOIN
            .register((e, l, d) -> { games.brennan.dungeontrain.event.PrefabUseHandler.onEntityJoinLevel(e, l, d); return false; }));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.ENTITY_JOIN
            .register((e, l, d) -> { games.brennan.dungeontrain.event.StartingBookEvents.onEntityJoinLevel(e, l, d); return false; }));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.ENTITY_JOIN
            .register((e, l, d) -> { games.brennan.dungeontrain.event.VillagerTrainSpawnEvents.onEntityJoin(e, l, d); return false; }));
        // EntityJoinLevelEvent — cancelling handlers (return true = cancel)
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.ENTITY_JOIN
            .register(games.brennan.dungeontrain.event.NetherBandBehaviourEvents::onEntityJoin));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.ENTITY_JOIN
            .register(games.brennan.dungeontrain.event.NetherBandBehaviourEvents::onFallingBlock));

        // MobEffectEvent.Remove — single cancelling handler
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.MOB_EFFECT_REMOVE
            .register(games.brennan.dungeontrain.event.CheatDetectionEvents::onEffectRemove));
    }

    /**
     * Player-connection handlers (login, logout, respawn, game-mode change),
     * fired by {@code NeoForgeConnectionBridge}. Login is registered with an
     * explicit HIGHEST tier for {@code CheatDetectionEvents} so it still runs
     * before the NORMAL-tier {@code AchievementEvents} (which reads the Free Play
     * flag CheatDetection sets) — the one load-bearing ordering in this category.
     * Everything else was single-tier NORMAL; order within NORMAL is irrelevant.
     * None of these events are dist-gated or cancelled by DT.
     */
    private static void registerPlayerConnection() {
        var HIGHEST = games.brennan.dungeontrain.platform.event.DtPriority.HIGHEST;

        // PlayerLoggedInEvent — HIGHEST (CheatDetection) then NORMAL (rest)
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGIN
            .register(HIGHEST, games.brennan.dungeontrain.event.CheatDetectionEvents::onLogin));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGIN
            .register(games.brennan.dungeontrain.event.StartingBookEvents::onPlayerLogin));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGIN
            .register(games.brennan.dungeontrain.event.PlayerJoinEvents::onPlayerLogin));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGIN
            .register(games.brennan.dungeontrain.event.BoardingProgressEvents::onPlayerLoggedIn));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGIN
            .register(games.brennan.dungeontrain.event.KeepInventoryCarryEvents::onPlayerLogin));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGIN
            .register(games.brennan.dungeontrain.event.AchievementEvents::onPlayerLoggedIn));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGIN
            .register(games.brennan.dungeontrain.event.DeathNoteRefreshEvents::onLogin));

        // PlayerLoggedOutEvent — NORMAL
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.narrative.LetterLecternEvents::onLogout));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.TrainTickEvents::onPlayerLoggedOut));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.DtpPlacementService::onPlayerLogout));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.TrainCinematographerEvents::onPlayerLoggedOut));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.RunStatsEvents::onPlayerLogout));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.PlayerJoinEvents::onPlayerLogout));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.BoardingProgressEvents::onPlayerLoggedOut));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.PlayerMobAdvancementEvents::onPlayerLoggedOut));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.AchievementEvents::onPlayerLoggedOut));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.DeathNoteRefreshEvents::onLogout));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.editor.VariantHotkeyState::onLogout));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.editor.ContainerHotkeyState::onLogout));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.editor.StagePanelController::onPlayerLoggedOut));

        // PlayerRespawnEvent — NORMAL
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_RESPAWN
            .register(games.brennan.dungeontrain.event.StartingBookEvents::onPlayerRespawn));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_RESPAWN
            .register(games.brennan.dungeontrain.event.RespawnDimensionEvents::onPlayerRespawn));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_RESPAWN
            .register(games.brennan.dungeontrain.event.BoardingProgressEvents::onPlayerRespawn));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_RESPAWN
            .register(games.brennan.dungeontrain.event.AchievementEvents::onPlayerRespawn));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_RESPAWN
            .register(games.brennan.dungeontrain.event.CheatDetectionEvents::onRespawn));

        // PlayerChangeGameModeEvent — NORMAL (single handler)
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_CHANGE_GAMEMODE
            .register(games.brennan.dungeontrain.event.CheatDetectionEvents::onChangeGameMode));
    }

    /**
     * Tick handlers (LevelTick.Post, ServerTick.Post, PlayerTick.Post,
     * EntityTick.Pre), fired by {@code NeoForgeTickBridge}. Every DT tick handler
     * was NORMAL priority and none are dist-gated, so all register at NORMAL in a
     * single tier. Registration order within an event is irrelevant (independent
     * handlers). None of these events are cancelled by DT.
     */
    private static void registerTicks() {
        // LevelTickEvent.Post (25 handlers)
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.echo.EchoEncounterEvents::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.ship.sable.SableKinematicTicker::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.StairsUsageEvents::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.StartingBookEvents::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.ResumeWatchdog::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.TrainTickEvents::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.RelayOutboxFlushEvents::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.DtpPlacementService::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.TrackPresenceEvents::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.NetherMobSpawner::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.TrainCinematographerEvents::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.SoulCampfireHealEvents::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.ZoneProgressEvents::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.RunStatsEvents::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.PlayerJoinEvents::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.CorridorCleanupEvents::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.BoardingProgressEvents::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.PlayerMobAdvancementEvents::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.PlayerMobAdvancementEvents::onEchoProximityScan));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.DeathNoteRefreshEvents::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.DeathNoteEchoController::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.CarriageGroupGapTicker::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.RoofRunEvents::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.MentionPresenceEvents::onLevelTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.editor.VariantEditorPreviewTicker::onLevelTick));

        // ServerTickEvent.Post (3 handlers)
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_TICK
            .register(games.brennan.dungeontrain.event.NarrativePoolRefreshEvents::onServerTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_TICK
            .register(games.brennan.dungeontrain.event.DeathReportBuffer::onServerTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_TICK
            .register(games.brennan.dungeontrain.event.SharedBookRefreshEvents::onServerTick));

        // PlayerTickEvent.Post (2 handlers)
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_TICK
            .register(games.brennan.dungeontrain.event.RunStatsEvents::onPlayerTick));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_TICK
            .register(games.brennan.dungeontrain.event.AchievementEvents::onPlayerTick));

        // EntityTickEvent.Pre (1 handler; never cancels)
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.ENTITY_TICK
            .register(games.brennan.dungeontrain.event.NetherBandZombificationGuard::onEntityTick));
    }

    /**
     * Server-lifecycle handlers (ServerStarting/Started/Stopping/Stopped),
     * fired by {@code NeoForgeLifecycleBridge}. Each is registered at the
     * {@code DtPriority} tier matching its former {@code @SubscribeEvent(priority)}
     * so the bridge's per-tier subscriptions reproduce the old cross-mod order.
     * Within a tier, order is irrelevant (all DT lifecycle handlers are
     * independent). {@code WorldLifecycleEvents} was {@code @EventBusSubscriber(
     * value = Dist.CLIENT)} — its registration is gated to the physical client so
     * the class never loads (nor runs) on a dedicated server, exactly as before.
     */
    private static void registerServerLifecycle() {
        var HIGHEST = games.brennan.dungeontrain.platform.event.DtPriority.HIGHEST;
        var HIGH = games.brennan.dungeontrain.platform.event.DtPriority.HIGH;
        var LOW = games.brennan.dungeontrain.platform.event.DtPriority.LOW;
        var LOWEST = games.brennan.dungeontrain.platform.event.DtPriority.LOWEST;

        // ServerStarting — HIGHEST, HIGH, NORMAL
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(HIGHEST, games.brennan.dungeontrain.editor.UserContentMigration::onServerStarting));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(HIGH, games.brennan.dungeontrain.editor.UserContentImporter::onServerStarting));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(HIGH, games.brennan.dungeontrain.editor.DtpacksMigration::onServerStarting));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.track.variant.TrackVariantRegistry::onServerStarting));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.track.variant.TrackVariantWeights::onServerStarting));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.train.CarriageContentsRegistry::onServerStarting));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.train.CarriageContentsWeights::onServerStarting));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.train.CarriageVariantRegistry::onServerStarting));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.train.CarriageWeights::onServerStarting));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.editor.EditorDevMode::onServerStarting));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.editor.BlockVariantPrefabStore::onServerStarting));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.editor.BlockLootDefaults::onServerStarting));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.editor.StageStore::onServerStarting));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.editor.LootPrefabStore::onServerStarting));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.editor.CarriagePartRegistry::onServerStarting));

        // ServerStarted — NORMAL, LOW (WorldLifecycleEvents = client-only)
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTED
            .register(games.brennan.dungeontrain.event.RelayOutboxFlushEvents::onServerStarted));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTED
            .register(games.brennan.dungeontrain.event.NarrativePoolRefreshEvents::onServerStarted));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTED
            .register(games.brennan.dungeontrain.event.DevMessageConsent::onServerStarted));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTED
            .register(games.brennan.dungeontrain.event.SharedBookRefreshEvents::onServerStarted));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTED
            .register(games.brennan.dungeontrain.editor.EditorDevMode::onServerStarted));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTED
            .register(LOW, games.brennan.dungeontrain.event.NetherBandContextEvents::onServerStarted));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTED
            .register(LOW, games.brennan.dungeontrain.event.TrainBootstrapEvents::onServerStarted));
        if (games.brennan.dungeontrain.platform.DtPlatform.get().isClient()) {
            safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTED
                .register(games.brennan.dungeontrain.event.WorldLifecycleEvents::onServerStarted));
        }

        // ServerStopping — NORMAL, LOWEST
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPING
            .register(games.brennan.dungeontrain.event.NetherBandContextEvents::onServerStopping));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPING
            .register(games.brennan.dungeontrain.echo.EchoEncounterEvents::onServerStopping));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPING
            .register(games.brennan.dungeontrain.event.ShipShutdownEvents::onServerStopping));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPING
            .register(games.brennan.dungeontrain.event.AchievementEvents::onServerStopping));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPING
            .register(games.brennan.dungeontrain.event.MentionPresenceEvents::onServerStopping));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPING
            .register(LOWEST, games.brennan.dungeontrain.event.ShutdownDiagnostics::onServerStopping));

        // ServerStopped — HIGHEST, NORMAL (WorldLifecycleEvents = client-only)
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(HIGHEST, games.brennan.dungeontrain.event.ShutdownDiagnostics::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.track.variant.TrackVariantWeights::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.track.variant.TrackVariantRegistry::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.narrative.NarrativeDataLoaders::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.train.CarriageContentsRegistry::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.train.CarriageContentsWeights::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.train.CarriageVariantRegistry::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.train.CarriageWeights::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.advancement.GlobalNarrativeProgress::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.event.ResumeWatchdog::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.event.NetworkConsentMirror::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.event.NarrativePoolRefreshEvents::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.event.DevMessageConsent::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.BlockVariantPrefabStore::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.StagePanelController::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.LootPrefabStore::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.EditorPartsStageFilter::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.EditorStampedCategoryState::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.EditorPartVisibility::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.StageStore::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.EditorStageSelection::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.VariantOverlayRenderer::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.BlockLootDefaults::onServerStopped));
        safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.CarriagePartRegistry::onServerStopped));
        if (games.brennan.dungeontrain.platform.DtPlatform.get().isClient()) {
            safe(() -> games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
                .register(games.brennan.dungeontrain.event.WorldLifecycleEvents::onServerStopped));
        }
    }
}
