package games.brennan.dungeontrain.platform.neoforge;

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
public final class NeoForgeServerEvents {

    private NeoForgeServerEvents() {}

    /** Register every converted server-side handler with its {@code DtEvents} field. */
    public static void register() {
        // Category registrations are added here, one block per converted category.

        // --- Command registration (RegisterCommandsEvent) --------------------
        // Single handler; order irrelevant.
        games.brennan.dungeontrain.platform.event.DtEvents.COMMAND_REGISTRATION
            .register(games.brennan.dungeontrain.event.CommandEvents::onRegisterCommands);

        // --- Server chat (ServerChatEvent) -----------------------------------
        // Single (observe-only) handler; order irrelevant.
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_CHAT
            .register(games.brennan.dungeontrain.event.MentionPresenceEvents::onServerChat);

        // --- Command execution (CommandEvent, cancellable) -------------------
        // Single handler; the Free Play gate cancels a tainting command.
        games.brennan.dungeontrain.platform.event.DtEvents.COMMAND_EXEC
            .register(games.brennan.dungeontrain.event.CheatDetectionEvents::onCommand);

        // --- Advancement earn (AdvancementEarnEvent) -------------------------
        // Single handler; order irrelevant.
        games.brennan.dungeontrain.platform.event.DtEvents.ADVANCEMENT_EARN
            .register(games.brennan.dungeontrain.event.AchievementEvents::onAdvancementEarn);

        registerServerLifecycle();
        registerTicks();
        registerPlayerConnection();
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
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGIN
            .register(HIGHEST, games.brennan.dungeontrain.event.CheatDetectionEvents::onLogin);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGIN
            .register(games.brennan.dungeontrain.event.StartingBookEvents::onPlayerLogin);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGIN
            .register(games.brennan.dungeontrain.event.PlayerJoinEvents::onPlayerLogin);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGIN
            .register(games.brennan.dungeontrain.event.BoardingProgressEvents::onPlayerLoggedIn);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGIN
            .register(games.brennan.dungeontrain.event.KeepInventoryCarryEvents::onPlayerLogin);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGIN
            .register(games.brennan.dungeontrain.event.AchievementEvents::onPlayerLoggedIn);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGIN
            .register(games.brennan.dungeontrain.event.DeathNoteRefreshEvents::onLogin);

        // PlayerLoggedOutEvent — NORMAL
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.narrative.LetterLecternEvents::onLogout);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.TrainTickEvents::onPlayerLoggedOut);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.DtpPlacementService::onPlayerLogout);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.TrainCinematographerEvents::onPlayerLoggedOut);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.RunStatsEvents::onPlayerLogout);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.PlayerJoinEvents::onPlayerLogout);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.BoardingProgressEvents::onPlayerLoggedOut);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.PlayerMobAdvancementEvents::onPlayerLoggedOut);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.AchievementEvents::onPlayerLoggedOut);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.event.DeathNoteRefreshEvents::onLogout);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.editor.VariantHotkeyState::onLogout);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.editor.ContainerHotkeyState::onLogout);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_LOGOUT
            .register(games.brennan.dungeontrain.editor.StagePanelController::onPlayerLoggedOut);

        // PlayerRespawnEvent — NORMAL
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_RESPAWN
            .register(games.brennan.dungeontrain.event.StartingBookEvents::onPlayerRespawn);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_RESPAWN
            .register(games.brennan.dungeontrain.event.RespawnDimensionEvents::onPlayerRespawn);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_RESPAWN
            .register(games.brennan.dungeontrain.event.BoardingProgressEvents::onPlayerRespawn);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_RESPAWN
            .register(games.brennan.dungeontrain.event.AchievementEvents::onPlayerRespawn);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_RESPAWN
            .register(games.brennan.dungeontrain.event.CheatDetectionEvents::onRespawn);

        // PlayerChangeGameModeEvent — NORMAL (single handler)
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_CHANGE_GAMEMODE
            .register(games.brennan.dungeontrain.event.CheatDetectionEvents::onChangeGameMode);
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
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.echo.EchoEncounterEvents::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.ship.sable.SableKinematicTicker::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.StairsUsageEvents::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.StartingBookEvents::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.ResumeWatchdog::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.TrainTickEvents::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.RelayOutboxFlushEvents::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.DtpPlacementService::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.TrackPresenceEvents::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.NetherMobSpawner::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.TrainCinematographerEvents::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.SoulCampfireHealEvents::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.ZoneProgressEvents::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.RunStatsEvents::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.PlayerJoinEvents::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.CorridorCleanupEvents::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.BoardingProgressEvents::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.PlayerMobAdvancementEvents::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.PlayerMobAdvancementEvents::onEchoProximityScan);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.DeathNoteRefreshEvents::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.DeathNoteEchoController::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.CarriageGroupGapTicker::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.RoofRunEvents::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.event.MentionPresenceEvents::onLevelTick);
        games.brennan.dungeontrain.platform.event.DtEvents.LEVEL_TICK
            .register(games.brennan.dungeontrain.editor.VariantEditorPreviewTicker::onLevelTick);

        // ServerTickEvent.Post (3 handlers)
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_TICK
            .register(games.brennan.dungeontrain.event.NarrativePoolRefreshEvents::onServerTick);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_TICK
            .register(games.brennan.dungeontrain.event.DeathReportBuffer::onServerTick);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_TICK
            .register(games.brennan.dungeontrain.event.SharedBookRefreshEvents::onServerTick);

        // PlayerTickEvent.Post (2 handlers)
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_TICK
            .register(games.brennan.dungeontrain.event.RunStatsEvents::onPlayerTick);
        games.brennan.dungeontrain.platform.event.DtEvents.PLAYER_TICK
            .register(games.brennan.dungeontrain.event.AchievementEvents::onPlayerTick);

        // EntityTickEvent.Pre (1 handler; never cancels)
        games.brennan.dungeontrain.platform.event.DtEvents.ENTITY_TICK
            .register(games.brennan.dungeontrain.event.NetherBandZombificationGuard::onEntityTick);
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
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(HIGHEST, games.brennan.dungeontrain.editor.UserContentMigration::onServerStarting);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(HIGH, games.brennan.dungeontrain.editor.UserContentImporter::onServerStarting);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(HIGH, games.brennan.dungeontrain.editor.DtpacksMigration::onServerStarting);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.track.variant.TrackVariantRegistry::onServerStarting);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.track.variant.TrackVariantWeights::onServerStarting);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.train.CarriageContentsRegistry::onServerStarting);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.train.CarriageContentsWeights::onServerStarting);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.train.CarriageVariantRegistry::onServerStarting);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.train.CarriageWeights::onServerStarting);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.editor.EditorDevMode::onServerStarting);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.editor.BlockVariantPrefabStore::onServerStarting);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.editor.BlockLootDefaults::onServerStarting);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.editor.StageStore::onServerStarting);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.editor.LootPrefabStore::onServerStarting);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTING
            .register(games.brennan.dungeontrain.editor.CarriagePartRegistry::onServerStarting);

        // ServerStarted — NORMAL, LOW (WorldLifecycleEvents = client-only)
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTED
            .register(games.brennan.dungeontrain.event.RelayOutboxFlushEvents::onServerStarted);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTED
            .register(games.brennan.dungeontrain.event.NarrativePoolRefreshEvents::onServerStarted);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTED
            .register(games.brennan.dungeontrain.event.DevMessageConsent::onServerStarted);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTED
            .register(games.brennan.dungeontrain.event.SharedBookRefreshEvents::onServerStarted);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTED
            .register(games.brennan.dungeontrain.editor.EditorDevMode::onServerStarted);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTED
            .register(LOW, games.brennan.dungeontrain.event.NetherBandContextEvents::onServerStarted);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTED
            .register(LOW, games.brennan.dungeontrain.event.TrainBootstrapEvents::onServerStarted);
        if (games.brennan.dungeontrain.platform.DtPlatform.get().isClient()) {
            games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STARTED
                .register(games.brennan.dungeontrain.event.WorldLifecycleEvents::onServerStarted);
        }

        // ServerStopping — NORMAL, LOWEST
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPING
            .register(games.brennan.dungeontrain.event.NetherBandContextEvents::onServerStopping);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPING
            .register(games.brennan.dungeontrain.echo.EchoEncounterEvents::onServerStopping);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPING
            .register(games.brennan.dungeontrain.event.ShipShutdownEvents::onServerStopping);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPING
            .register(games.brennan.dungeontrain.event.AchievementEvents::onServerStopping);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPING
            .register(games.brennan.dungeontrain.event.MentionPresenceEvents::onServerStopping);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPING
            .register(LOWEST, games.brennan.dungeontrain.event.ShutdownDiagnostics::onServerStopping);

        // ServerStopped — HIGHEST, NORMAL (WorldLifecycleEvents = client-only)
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(HIGHEST, games.brennan.dungeontrain.event.ShutdownDiagnostics::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.track.variant.TrackVariantWeights::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.track.variant.TrackVariantRegistry::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.narrative.NarrativeDataLoaders::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.train.CarriageContentsRegistry::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.train.CarriageContentsWeights::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.train.CarriageVariantRegistry::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.train.CarriageWeights::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.advancement.GlobalNarrativeProgress::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.event.ResumeWatchdog::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.event.NetworkConsentMirror::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.event.NarrativePoolRefreshEvents::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.event.DevMessageConsent::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.BlockVariantPrefabStore::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.StagePanelController::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.LootPrefabStore::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.EditorPartsStageFilter::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.EditorStampedCategoryState::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.EditorPartVisibility::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.StageStore::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.EditorStageSelection::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.VariantOverlayRenderer::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.BlockLootDefaults::onServerStopped);
        games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
            .register(games.brennan.dungeontrain.editor.CarriagePartRegistry::onServerStopped);
        if (games.brennan.dungeontrain.platform.DtPlatform.get().isClient()) {
            games.brennan.dungeontrain.platform.event.DtEvents.SERVER_STOPPED
                .register(games.brennan.dungeontrain.event.WorldLifecycleEvents::onServerStopped);
        }
    }
}
