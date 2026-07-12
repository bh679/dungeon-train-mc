package games.brennan.dungeontrain.fabric.event;

import games.brennan.dungeontrain.platform.event.DtEvents;

/**
 * Root-side registrar that wires Dungeon Train's converted, still-root-resident
 * <b>client-side</b> handlers to their {@code DtEvents} fields — the client
 * counterpart to {@link NeoForgeServerEvents}. Invoked once from the
 * {@code DungeonTrain} constructor, <b>only on the physical client</b> (guarded by
 * {@code DtPlatform.get().isClient()} at the callsite), immediately after
 * {@code NeoForgeServerEvents.register()} and before any {@code NeoForge*Bridge}
 * could fire an event.
 *
 * <p><b>Dist-gating:</b> because the callsite is client-gated, this class — and
 * every client handler class it method-references — is only ever loaded on the
 * client. On a dedicated server it is never touched, so client-only vanilla types
 * (Minecraft, Screen, KeyMapping, …) never classload there. The matching
 * {@code NeoForge*Bridge} classes are all {@code @EventBusSubscriber(value =
 * Dist.CLIENT)} for the same reason.</p>
 *
 * <p>As each handler class migrates into {@code :common} in a later fixpoint, its
 * registration line moves from here into a {@code DungeonTrainCommon.initClient()}
 * seam and this class shrinks toward empty (same trajectory as
 * {@link NeoForgeServerEvents}).</p>
 */
public final class FabricClientEvents {

    private FabricClientEvents() {}

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

    /** Register every converted client-side handler with its {@code DtEvents} field. */
    public static void register() {
        registerClientTick();
        registerClientConnection();
        registerRegisterStyle();
        registerTooltipAndRender();
        registerInput();
        registerScreen();
        registerRenderLevel();
    }

    /**
     * World-render handlers, fired by {@code NeoForgeClientRenderLevelBridge}: all 12
     * former {@code RenderLevelStageEvent} handlers, which gated exclusively on
     * {@code AFTER_TRANSLUCENT_BLOCKS}. The bridge owns that stage check now, so these
     * fire only at that stage (order irrelevant; all NORMAL).
     */
    private static void registerRenderLevel() {
        safe(() -> DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.CarriageGroupGapDebugRenderer::onRenderLevelStage));
        safe(() -> DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.templateblocks.TemplateBlocksMenuRenderer::onRenderLevelStage));
        safe(() -> DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.plot.EditorHelpPanelRenderer::onRenderLevelStage));
        safe(() -> DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer::onRenderLevelStage));
        safe(() -> DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.stagepanel.StagePanelMenuRenderer::onRenderLevelStage));
        safe(() -> DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuRenderer::onRenderLevelStage));
        safe(() -> DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantWireframeRenderer::onRenderLevelStage));
        safe(() -> DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantLockIdRenderer::onRenderLevelStage));
        safe(() -> DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.containercontents.ContainerContentsMenuRenderer::onRenderLevelStage));
        safe(() -> DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.parts.PartPositionMenuRenderer::onRenderLevelStage));
        safe(() -> DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer::onRenderLevelStage));
        safe(() -> DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.CommandMenuRenderer::onRenderLevelStage));
    }

    /**
     * {@code ScreenEvent} sub-event handlers, fired by
     * {@code NeoForgeClientScreenBridge}: Opening (4), Init.Post (5), Render.Pre (4),
     * Render.Post (1), Closing (4). All NORMAL. Opening is cancellable/mutable (see
     * {@code DtEvents.SCREEN_OPENING}); the rest are observers/mutators. Order within
     * a subtype is irrelevant (independent handlers).
     */
    private static void registerScreen() {
        // ScreenEvent.Opening
        safe(() -> DtEvents.SCREEN_OPENING
            .register(games.brennan.dungeontrain.client.BookReadClientEvents::onScreenOpening));
        safe(() -> DtEvents.SCREEN_OPENING
            .register(games.brennan.dungeontrain.client.CinematicInputHandler::onScreenOpening));
        safe(() -> DtEvents.SCREEN_OPENING
            .register(games.brennan.dungeontrain.client.CinematicPreloadGate::onScreenOpening));
        safe(() -> DtEvents.SCREEN_OPENING
            .register(games.brennan.dungeontrain.client.DeathScreenLayoutHandler::onScreenOpening));

        // ScreenEvent.Init.Post
        // DevQuickWorldHandler::onScreenInitPost migrated to DungeonTrainCommon.initClient()
        // (handler class now in :common).
        safe(() -> DtEvents.SCREEN_INIT_POST
            .register(games.brennan.dungeontrain.client.DeveloperWelcomePopupHandler::onScreenInitPost));
        safe(() -> DtEvents.SCREEN_INIT_POST
            .register(games.brennan.dungeontrain.client.PauseMenuLayoutHandler::onScreenInitPost));
        safe(() -> DtEvents.SCREEN_INIT_POST
            .register(games.brennan.dungeontrain.client.TitleScreenLayoutHandler::onScreenInitPost));
        safe(() -> DtEvents.SCREEN_INIT_POST
            .register(games.brennan.dungeontrain.client.chat.MenuChatButtonHandler::onScreenInitPost));

        // ScreenEvent.Render.Pre
        safe(() -> DtEvents.SCREEN_RENDER_PRE
            .register(games.brennan.dungeontrain.client.DefaultAdvancementsTab::onScreenRenderPre));
        // DevQuickWorldHandler::onScreenRenderPre and
        // PendingStartingDimensionSyncHandler::onRenderPre migrated to
        // DungeonTrainCommon.initClient() (handler classes now in :common).
        safe(() -> DtEvents.SCREEN_RENDER_PRE
            .register(games.brennan.dungeontrain.client.PauseMenuLayoutHandler::onScreenRenderPre));

        // ScreenEvent.Render.Post
        safe(() -> DtEvents.SCREEN_RENDER_POST
            .register(games.brennan.dungeontrain.client.chat.MenuChatButtonHandler::onRenderPost));

        // ScreenEvent.Closing
        safe(() -> DtEvents.SCREEN_CLOSING
            .register(games.brennan.dungeontrain.client.AdvancementsScreenWatcher::onScreenClosing));
        safe(() -> DtEvents.SCREEN_CLOSING
            .register(games.brennan.dungeontrain.client.BookReadClientEvents::onScreenClosing));
        safe(() -> DtEvents.SCREEN_CLOSING
            .register(games.brennan.dungeontrain.client.LetterLecternClientEvents::onScreenClosing));
        safe(() -> DtEvents.SCREEN_CLOSING
            .register(games.brennan.dungeontrain.client.StartingBookClientEvents::onScreenClosing));
    }

    /**
     * Cancellable client input handlers, fired by {@code NeoForgeClientInputBridge}:
     * {@code MouseButton.Pre} (10), {@code InteractionKeyMappingTriggered} (12),
     * {@code LeftClickBlock} (10). See each {@code DtEvents} field for the exact
     * cancellation contract. Registration order is load-bearing here (see below).
     */
    private static void registerInput() {
        // MouseButton.Pre — 9 non-cancelling menu observers first, the sole canceller
        // (CinematicInputHandler) last, so observers always run before any cancel.
        safe(() -> DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.templateblocks.TemplateBlocksMenuInputHandler::onMouseButton));
        safe(() -> DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.plot.EditorPlotPanelInputHandler::onMouseButton));
        safe(() -> DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuInputHandler::onMouseButton));
        safe(() -> DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.plot.EditorHelpPanelInputHandler::onMouseButton));
        safe(() -> DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.stagepanel.StagePanelMenuInputHandler::onMouseButton));
        safe(() -> DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuInputHandler::onMouseButton));
        safe(() -> DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.containercontents.ContainerContentsMenuInputHandler::onMouseButton));
        safe(() -> DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.parts.PartPositionMenuInputHandler::onMouseButton));
        safe(() -> DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.CommandMenuInputHandler::onMouseButton));
        safe(() -> DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.CinematicInputHandler::onMouseButton));

        // InteractionKeyMappingTriggered — two observers first (they always record their
        // hotkey state), then the nine menu cancellers and the cinematic canceller.
        safe(() -> DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.ContainerHotkeyClient.ContainerTickWatcher::onInteraction));
        safe(() -> DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.VariantHotkeyClient.TickWatcher::onInteraction));
        safe(() -> DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.templateblocks.TemplateBlocksMenuInputHandler::onInteraction));
        safe(() -> DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.plot.EditorPlotPanelInputHandler::onInteraction));
        safe(() -> DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuInputHandler::onInteraction));
        safe(() -> DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.plot.EditorHelpPanelInputHandler::onInteraction));
        safe(() -> DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.stagepanel.StagePanelMenuInputHandler::onInteraction));
        safe(() -> DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuInputHandler::onInteraction));
        safe(() -> DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.containercontents.ContainerContentsMenuInputHandler::onInteraction));
        safe(() -> DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.parts.PartPositionMenuInputHandler::onInteraction));
        safe(() -> DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.CommandMenuInputHandler::onInteraction));
        safe(() -> DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.CinematicInputHandler::onInteraction));

        // LeftClickBlock — HIGHEST tier: nine menu cancellers; NORMAL tier: one observer.
        var HIGHEST = games.brennan.dungeontrain.platform.event.DtPriority.HIGHEST;
        safe(() -> DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.templateblocks.TemplateBlocksMenuInputHandler::onLeftClickBlock));
        safe(() -> DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.plot.EditorPlotPanelInputHandler::onLeftClickBlock));
        safe(() -> DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuInputHandler::onLeftClickBlock));
        safe(() -> DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.plot.EditorHelpPanelInputHandler::onLeftClickBlock));
        safe(() -> DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.stagepanel.StagePanelMenuInputHandler::onLeftClickBlock));
        safe(() -> DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuInputHandler::onLeftClickBlock));
        safe(() -> DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.containercontents.ContainerContentsMenuInputHandler::onLeftClickBlock));
        safe(() -> DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.parts.PartPositionMenuInputHandler::onLeftClickBlock));
        safe(() -> DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.CommandMenuInputHandler::onLeftClickBlock));
        safe(() -> DtEvents.LEFT_CLICK_BLOCK
            .register(games.brennan.dungeontrain.client.snapshot.RideSnapshotDirector::onLeftClickBlock));

        // InputEvent.Key (not cancellable) — two independent handlers, order irrelevant.
        safe(() -> DtEvents.KEY_INPUT
            .register(games.brennan.dungeontrain.client.CinematicInputHandler::onKey));
        safe(() -> DtEvents.KEY_INPUT
            .register(games.brennan.dungeontrain.client.menu.CommandMenuInputHandler::onKey));

        // InputEvent.MouseScrollingEvent (cancellable) — sole handler.
        safe(() -> DtEvents.MOUSE_SCROLL
            .register(games.brennan.dungeontrain.client.CinematicInputHandler::onMouseScroll));

        // EntityInteract / AttackEntity — client-only snapshot observers (registered here, not in
        // NeoForgeServerEvents, so the client class never loads on a dedicated server; they self-guard
        // on isClientSide anyway). The shared interact/attack bridges fire these on both sides; the
        // buckets are client-populated only.
        safe(() -> DtEvents.ENTITY_INTERACT
            .register(games.brennan.dungeontrain.client.snapshot.RideSnapshotDirector::onEntityInteract));
        safe(() -> DtEvents.ATTACK_ENTITY
            .register(games.brennan.dungeontrain.client.snapshot.RideSnapshotDirector::onAttackEntity));

        // RightClickBlock HIGHEST tier — client-only menu canceller (registered here, not in
        // NeoForgeServerEvents, so the client class never loads on a dedicated server). The shared
        // NeoForgeRightClickBlockBridge fires the HIGHEST tier on both sides; the bucket is empty
        // server-side because this is the only HIGHEST handler and it registers client-only.
        safe(() -> DtEvents.RIGHT_CLICK_BLOCK
            .register(games.brennan.dungeontrain.platform.event.DtPriority.HIGHEST,
                games.brennan.dungeontrain.client.menu.CommandMenuInputHandler::onRightClickBlock));
    }

    /**
     * Client tooltip / render / audio / chat handlers, fired by
     * {@code NeoForgeClientTooltipBridge} and {@code NeoForgeClientRenderBridge}:
     * {@code ItemTooltipEvent} (1), {@code GatherEffectScreenTooltipsEvent} (2),
     * {@code RenderTooltipEvent.GatherComponents} (1), {@code ViewportEvent.ComputeFogColor}
     * (3), {@code SelectMusicEvent} (2), {@code RenderHandEvent} (1, cancellable),
     * {@code ClientChatEvent} (1). All NORMAL; the mutation carriers pass through the
     * live event so semantics are identical. Order within a tier is irrelevant.
     */
    private static void registerTooltipAndRender() {
        // ItemTooltipEvent
        safe(() -> DtEvents.ITEM_TOOLTIP
            .register(games.brennan.dungeontrain.event.PrefabUseHandler::onTooltip));
        // GatherEffectScreenTooltipsEvent
        safe(() -> DtEvents.EFFECT_TOOLTIP
            .register(games.brennan.dungeontrain.client.WarmthOfTheFireTooltip::onGatherEffectTooltips));
        safe(() -> DtEvents.EFFECT_TOOLTIP
            .register(games.brennan.dungeontrain.client.FreePlayTooltip::onGatherEffectTooltips));
        // RenderTooltipEvent.GatherComponents
        safe(() -> DtEvents.GATHER_TOOLTIP_COMPONENTS
            .register(games.brennan.dungeontrain.client.tooltip.PrefabTooltipEvents.ForgeBus::onGatherComponents));
        // ViewportEvent.ComputeFogColor
        safe(() -> DtEvents.FOG_COLOR
            .register(games.brennan.dungeontrain.client.VoidSkyEvents::onComputeFogColor));
        safe(() -> DtEvents.FOG_COLOR
            .register(games.brennan.dungeontrain.client.NetherFogEvents::onComputeFogColor));
        safe(() -> DtEvents.FOG_COLOR
            .register(games.brennan.dungeontrain.client.UpsideDownFogEvents::onComputeFogColor));
        // SelectMusicEvent
        safe(() -> DtEvents.SELECT_MUSIC
            .register(games.brennan.dungeontrain.client.VoidSkyEvents::onSelectMusic));
        safe(() -> DtEvents.SELECT_MUSIC
            .register(games.brennan.dungeontrain.client.NetherFogEvents::onSelectMusic));
        // RenderHandEvent (cancellable)
        safe(() -> DtEvents.RENDER_HAND
            .register(games.brennan.dungeontrain.client.CinematicInputHandler::onRenderHand));
        // ClientChatEvent
        safe(() -> DtEvents.CLIENT_CHAT
            .register(games.brennan.dungeontrain.client.DevMessageConsentClient::onClientChat));
    }

    /**
     * Register-style client events converted to declarative tables (mirroring
     * {@code COMMAND_REGISTRATION} in 2a), fired by {@code NeoForgeClientCommandBridge}
     * (game bus) and {@code NeoForgeClientRegistryBridge} (mod bus):
     * {@code RegisterClientCommandsEvent} (1), {@code RegisterKeyMappingsEvent} (6),
     * {@code RegisterGuiLayersEvent} (4), {@code RegisterClientTooltipComponentFactoriesEvent}
     * (1). None cancellable; all independent (order irrelevant).
     */
    private static void registerRegisterStyle() {
        // RegisterClientCommandsEvent
        safe(() -> DtEvents.CLIENT_COMMAND_REGISTRATION
            .register(games.brennan.dungeontrain.client.NewWorldCommand::onRegisterClientCommands));

        // RegisterKeyMappingsEvent
        safe(() -> DtEvents.KEY_MAPPING_REGISTRATION
            .register(games.brennan.dungeontrain.client.CinematographerHotkeyClient::onRegister));
        safe(() -> DtEvents.KEY_MAPPING_REGISTRATION
            .register(games.brennan.dungeontrain.client.TemplateBlocksHotkeyClient::onRegister));
        safe(() -> DtEvents.KEY_MAPPING_REGISTRATION
            .register(games.brennan.dungeontrain.client.ContainerHotkeyClient::onRegister));
        safe(() -> DtEvents.KEY_MAPPING_REGISTRATION
            .register(games.brennan.dungeontrain.fabric.client.menu.CommandMenuKeyBindings::onRegisterKeys));
        safe(() -> DtEvents.KEY_MAPPING_REGISTRATION
            .register(games.brennan.dungeontrain.client.VariantHotkeyClient::onRegister));
        safe(() -> DtEvents.KEY_MAPPING_REGISTRATION
            .register(games.brennan.dungeontrain.client.ManualSpawnHotkeyClient::onRegister));

        // RegisterGuiLayersEvent
        safe(() -> DtEvents.GUI_LAYER_REGISTRATION
            .register(games.brennan.dungeontrain.client.CinematicSkipHudOverlay::onRegisterGuiLayers));
        safe(() -> DtEvents.GUI_LAYER_REGISTRATION
            .register(games.brennan.dungeontrain.client.VersionHudOverlay::onRegisterGuiLayers));
        safe(() -> DtEvents.GUI_LAYER_REGISTRATION
            .register(games.brennan.dungeontrain.client.VariantHoverHudOverlay::onRegisterGuiLayers));
        safe(() -> DtEvents.GUI_LAYER_REGISTRATION
            .register(games.brennan.dungeontrain.client.EditorStatusHudOverlay::onRegisterGuiLayers));

        // RegisterClientTooltipComponentFactoriesEvent
        safe(() -> DtEvents.CLIENT_TOOLTIP_FACTORY_REGISTRATION
            .register(games.brennan.dungeontrain.client.tooltip.PrefabTooltipEvents.ModBus::onRegisterFactories));

        // RegisterClientReloadListenersEvent (client resource channel)
        safe(() -> DtEvents.CLIENT_RELOAD_LISTENER_REGISTRATION
            .register(games.brennan.dungeontrain.client.localization.LocalizationCreditsClientLoaders::registerReloadListeners));
    }

    /**
     * Client player-network handlers ({@code LoggingIn} ×3, {@code LoggingOut} ×15),
     * fired by {@code NeoForgeClientConnectionBridge}. All were NORMAL priority, not
     * cancellable, and ignore the event object; order within a tier is irrelevant.
     */
    private static void registerClientConnection() {
        // ClientPlayerNetworkEvent.LoggingIn
        safe(() -> DtEvents.CLIENT_LOGGING_IN
            .register(games.brennan.dungeontrain.client.snapshot.RideSnapshotDirector::onLoggingIn));
        safe(() -> DtEvents.CLIENT_LOGGING_IN
            .register(games.brennan.dungeontrain.client.NetworkConsentSyncClient::onLoggingIn));
        safe(() -> DtEvents.CLIENT_LOGGING_IN
            .register(games.brennan.dungeontrain.client.DevMessageConsentClient::onLoggingIn));

        // ClientPlayerNetworkEvent.LoggingOut
        safe(() -> DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.snapshot.RideSnapshotDirector::onLoggingOut));
        safe(() -> DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.VoidSkyEvents::onLoggingOut));
        safe(() -> DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.CinematicInputHandler::onLoggingOut));
        safe(() -> DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.CinematicPreloadGate::onLoggingOut));
        safe(() -> DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.NetherFogEvents::onLoggingOut));
        safe(() -> DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.menu.ClientStageBlocks::onLoggingOut));
        safe(() -> DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer::onLoggingOut));
        safe(() -> DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantWireframeRenderer::onLoggingOut));
        safe(() -> DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantLockIdRenderer::onLoggingOut));
        safe(() -> DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.menu.ClientPartVisibility::onLoggingOut));
        safe(() -> DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.menu.PrefabClientLifecycleEvents::onLoggingOut));
        safe(() -> DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer::onLoggingOut));
        safe(() -> DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.UpsideDownFogEvents::onLoggingOut));
        safe(() -> DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.EditorStatusHudOverlay::onLoggingOut));
        safe(() -> DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.SpawnDeckHold::onLoggingOut));
    }

    /**
     * Client-tick handlers (Post ×26, Pre ×1), fired by
     * {@code NeoForgeClientTickBridge}. All were NORMAL priority, not cancellable,
     * and ignore the event object; order within the tier is irrelevant (independent
     * handlers). The sole {@code Pre} handler ({@code CinematicInputHandler}) drives
     * the cinematic clock; the rest are {@code Post}.
     */
    private static void registerClientTick() {
        // ClientTickEvent.Pre
        safe(() -> DtEvents.CLIENT_TICK_PRE
            .register(games.brennan.dungeontrain.client.CinematicInputHandler::onClientTick));

        // ClientTickEvent.Post
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.BookReadClientEvents::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.CinematographerHotkeyClient.CinematographerTickWatcher::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.snapshot.RideSnapshotDirector::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.VoidSkyEvents::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.TemplateBlocksHotkeyClient.TickWatcher::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.ResumeRenderDiagnostics::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.AdvancementsHintClient::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.EditorAutoOpenHandler::onClientTickPost));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.CinematicPreloadGate::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.NetherFogEvents::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.NewWorldCommand::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.ContainerHotkeyClient.ContainerTickWatcher::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.menu.templateblocks.TemplateBlocksMenuInputHandler::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.menu.plot.EditorPlotPanelInputHandler::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuInputHandler::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.menu.plot.EditorHelpPanelInputHandler::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.fabric.client.menu.CommandMenuToggleHandler::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.menu.stagepanel.StagePanelMenuInputHandler::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuInputHandler::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.menu.containercontents.ContainerContentsMenuInputHandler::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.menu.parts.PartPositionMenuInputHandler::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.VariantHotkeyClient.TickWatcher::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.ManualSpawnHotkeyClient.ManualSpawnTickWatcher::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.sound.TrainSoundManager::onClientTick));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.DeveloperWelcomePopupHandler::onClientTickPost));
        safe(() -> DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.SpawnDeckHold::onClientTick));
    }
}
