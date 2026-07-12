package games.brennan.dungeontrain.platform.neoforge;

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
public final class NeoForgeClientEvents {

    private NeoForgeClientEvents() {}

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
        DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.CarriageGroupGapDebugRenderer::onRenderLevelStage);
        DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.templateblocks.TemplateBlocksMenuRenderer::onRenderLevelStage);
        DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.plot.EditorHelpPanelRenderer::onRenderLevelStage);
        DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer::onRenderLevelStage);
        DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.stagepanel.StagePanelMenuRenderer::onRenderLevelStage);
        DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuRenderer::onRenderLevelStage);
        DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantWireframeRenderer::onRenderLevelStage);
        DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantLockIdRenderer::onRenderLevelStage);
        DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.containercontents.ContainerContentsMenuRenderer::onRenderLevelStage);
        DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.parts.PartPositionMenuRenderer::onRenderLevelStage);
        DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer::onRenderLevelStage);
        DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT
            .register(games.brennan.dungeontrain.client.menu.CommandMenuRenderer::onRenderLevelStage);
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
        DtEvents.SCREEN_OPENING
            .register(games.brennan.dungeontrain.client.BookReadClientEvents::onScreenOpening);
        DtEvents.SCREEN_OPENING
            .register(games.brennan.dungeontrain.client.CinematicInputHandler::onScreenOpening);
        DtEvents.SCREEN_OPENING
            .register(games.brennan.dungeontrain.client.CinematicPreloadGate::onScreenOpening);
        DtEvents.SCREEN_OPENING
            .register(games.brennan.dungeontrain.client.DeathScreenLayoutHandler::onScreenOpening);

        // ScreenEvent.Init.Post
        // DevQuickWorldHandler::onScreenInitPost migrated to DungeonTrainCommon.initClient()
        // (handler class now in :common).
        DtEvents.SCREEN_INIT_POST
            .register(games.brennan.dungeontrain.client.DeveloperWelcomePopupHandler::onScreenInitPost);
        DtEvents.SCREEN_INIT_POST
            .register(games.brennan.dungeontrain.client.PauseMenuLayoutHandler::onScreenInitPost);
        DtEvents.SCREEN_INIT_POST
            .register(games.brennan.dungeontrain.client.TitleScreenLayoutHandler::onScreenInitPost);
        DtEvents.SCREEN_INIT_POST
            .register(games.brennan.dungeontrain.client.chat.MenuChatButtonHandler::onScreenInitPost);

        // ScreenEvent.Render.Pre
        DtEvents.SCREEN_RENDER_PRE
            .register(games.brennan.dungeontrain.client.DefaultAdvancementsTab::onScreenRenderPre);
        // DevQuickWorldHandler::onScreenRenderPre and
        // PendingStartingDimensionSyncHandler::onRenderPre migrated to
        // DungeonTrainCommon.initClient() (handler classes now in :common).
        DtEvents.SCREEN_RENDER_PRE
            .register(games.brennan.dungeontrain.client.PauseMenuLayoutHandler::onScreenRenderPre);

        // ScreenEvent.Render.Post
        DtEvents.SCREEN_RENDER_POST
            .register(games.brennan.dungeontrain.client.chat.MenuChatButtonHandler::onRenderPost);

        // ScreenEvent.Closing
        DtEvents.SCREEN_CLOSING
            .register(games.brennan.dungeontrain.client.AdvancementsScreenWatcher::onScreenClosing);
        DtEvents.SCREEN_CLOSING
            .register(games.brennan.dungeontrain.client.BookReadClientEvents::onScreenClosing);
        DtEvents.SCREEN_CLOSING
            .register(games.brennan.dungeontrain.client.LetterLecternClientEvents::onScreenClosing);
        DtEvents.SCREEN_CLOSING
            .register(games.brennan.dungeontrain.client.StartingBookClientEvents::onScreenClosing);
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
        DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.templateblocks.TemplateBlocksMenuInputHandler::onMouseButton);
        DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.plot.EditorPlotPanelInputHandler::onMouseButton);
        DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuInputHandler::onMouseButton);
        DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.plot.EditorHelpPanelInputHandler::onMouseButton);
        DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.stagepanel.StagePanelMenuInputHandler::onMouseButton);
        DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuInputHandler::onMouseButton);
        DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.containercontents.ContainerContentsMenuInputHandler::onMouseButton);
        DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.parts.PartPositionMenuInputHandler::onMouseButton);
        DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.menu.CommandMenuInputHandler::onMouseButton);
        DtEvents.MOUSE_BUTTON_PRE
            .register(games.brennan.dungeontrain.client.CinematicInputHandler::onMouseButton);

        // InteractionKeyMappingTriggered — two observers first (they always record their
        // hotkey state), then the nine menu cancellers and the cinematic canceller.
        DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.ContainerHotkeyClient.ContainerTickWatcher::onInteraction);
        DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.VariantHotkeyClient.TickWatcher::onInteraction);
        DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.templateblocks.TemplateBlocksMenuInputHandler::onInteraction);
        DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.plot.EditorPlotPanelInputHandler::onInteraction);
        DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuInputHandler::onInteraction);
        DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.plot.EditorHelpPanelInputHandler::onInteraction);
        DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.stagepanel.StagePanelMenuInputHandler::onInteraction);
        DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuInputHandler::onInteraction);
        DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.containercontents.ContainerContentsMenuInputHandler::onInteraction);
        DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.parts.PartPositionMenuInputHandler::onInteraction);
        DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.menu.CommandMenuInputHandler::onInteraction);
        DtEvents.INTERACTION_KEY
            .register(games.brennan.dungeontrain.client.CinematicInputHandler::onInteraction);

        // LeftClickBlock — HIGHEST tier: nine menu cancellers; NORMAL tier: one observer.
        var HIGHEST = games.brennan.dungeontrain.platform.event.DtPriority.HIGHEST;
        DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.templateblocks.TemplateBlocksMenuInputHandler::onLeftClickBlock);
        DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.plot.EditorPlotPanelInputHandler::onLeftClickBlock);
        DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuInputHandler::onLeftClickBlock);
        DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.plot.EditorHelpPanelInputHandler::onLeftClickBlock);
        DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.stagepanel.StagePanelMenuInputHandler::onLeftClickBlock);
        DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuInputHandler::onLeftClickBlock);
        DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.containercontents.ContainerContentsMenuInputHandler::onLeftClickBlock);
        DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.parts.PartPositionMenuInputHandler::onLeftClickBlock);
        DtEvents.LEFT_CLICK_BLOCK
            .register(HIGHEST, games.brennan.dungeontrain.client.menu.CommandMenuInputHandler::onLeftClickBlock);
        DtEvents.LEFT_CLICK_BLOCK
            .register(games.brennan.dungeontrain.client.snapshot.RideSnapshotDirector::onLeftClickBlock);

        // InputEvent.Key (not cancellable) — two independent handlers, order irrelevant.
        DtEvents.KEY_INPUT
            .register(games.brennan.dungeontrain.client.CinematicInputHandler::onKey);
        DtEvents.KEY_INPUT
            .register(games.brennan.dungeontrain.client.menu.CommandMenuInputHandler::onKey);

        // InputEvent.MouseScrollingEvent (cancellable) — sole handler.
        DtEvents.MOUSE_SCROLL
            .register(games.brennan.dungeontrain.client.CinematicInputHandler::onMouseScroll);

        // RightClickBlock HIGHEST tier — client-only menu canceller (registered here, not in
        // NeoForgeServerEvents, so the client class never loads on a dedicated server). The shared
        // NeoForgeRightClickBlockBridge fires the HIGHEST tier on both sides; the bucket is empty
        // server-side because this is the only HIGHEST handler and it registers client-only.
        DtEvents.RIGHT_CLICK_BLOCK
            .register(games.brennan.dungeontrain.platform.event.DtPriority.HIGHEST,
                games.brennan.dungeontrain.client.menu.CommandMenuInputHandler::onRightClickBlock);
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
        DtEvents.ITEM_TOOLTIP
            .register(games.brennan.dungeontrain.event.PrefabUseHandler::onTooltip);
        // GatherEffectScreenTooltipsEvent
        DtEvents.EFFECT_TOOLTIP
            .register(games.brennan.dungeontrain.client.WarmthOfTheFireTooltip::onGatherEffectTooltips);
        DtEvents.EFFECT_TOOLTIP
            .register(games.brennan.dungeontrain.client.FreePlayTooltip::onGatherEffectTooltips);
        // RenderTooltipEvent.GatherComponents
        DtEvents.GATHER_TOOLTIP_COMPONENTS
            .register(games.brennan.dungeontrain.client.tooltip.PrefabTooltipEvents.ForgeBus::onGatherComponents);
        // ViewportEvent.ComputeFogColor
        DtEvents.FOG_COLOR
            .register(games.brennan.dungeontrain.client.VoidSkyEvents::onComputeFogColor);
        DtEvents.FOG_COLOR
            .register(games.brennan.dungeontrain.client.NetherFogEvents::onComputeFogColor);
        DtEvents.FOG_COLOR
            .register(games.brennan.dungeontrain.client.UpsideDownFogEvents::onComputeFogColor);
        // SelectMusicEvent
        DtEvents.SELECT_MUSIC
            .register(games.brennan.dungeontrain.client.VoidSkyEvents::onSelectMusic);
        DtEvents.SELECT_MUSIC
            .register(games.brennan.dungeontrain.client.NetherFogEvents::onSelectMusic);
        // RenderHandEvent (cancellable)
        DtEvents.RENDER_HAND
            .register(games.brennan.dungeontrain.client.CinematicInputHandler::onRenderHand);
        // ClientChatEvent
        DtEvents.CLIENT_CHAT
            .register(games.brennan.dungeontrain.client.DevMessageConsentClient::onClientChat);
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
        DtEvents.CLIENT_COMMAND_REGISTRATION
            .register(games.brennan.dungeontrain.client.NewWorldCommand::onRegisterClientCommands);

        // RegisterKeyMappingsEvent
        DtEvents.KEY_MAPPING_REGISTRATION
            .register(games.brennan.dungeontrain.client.CinematographerHotkeyClient::onRegister);
        DtEvents.KEY_MAPPING_REGISTRATION
            .register(games.brennan.dungeontrain.client.TemplateBlocksHotkeyClient::onRegister);
        DtEvents.KEY_MAPPING_REGISTRATION
            .register(games.brennan.dungeontrain.client.ContainerHotkeyClient::onRegister);
        DtEvents.KEY_MAPPING_REGISTRATION
            .register(games.brennan.dungeontrain.client.menu.CommandMenuKeyBindings::onRegisterKeys);
        DtEvents.KEY_MAPPING_REGISTRATION
            .register(games.brennan.dungeontrain.client.VariantHotkeyClient::onRegister);
        DtEvents.KEY_MAPPING_REGISTRATION
            .register(games.brennan.dungeontrain.client.ManualSpawnHotkeyClient::onRegister);

        // RegisterGuiLayersEvent
        DtEvents.GUI_LAYER_REGISTRATION
            .register(games.brennan.dungeontrain.client.CinematicSkipHudOverlay::onRegisterGuiLayers);
        DtEvents.GUI_LAYER_REGISTRATION
            .register(games.brennan.dungeontrain.client.VersionHudOverlay::onRegisterGuiLayers);
        DtEvents.GUI_LAYER_REGISTRATION
            .register(games.brennan.dungeontrain.client.VariantHoverHudOverlay::onRegisterGuiLayers);
        DtEvents.GUI_LAYER_REGISTRATION
            .register(games.brennan.dungeontrain.client.EditorStatusHudOverlay::onRegisterGuiLayers);

        // RegisterClientTooltipComponentFactoriesEvent
        DtEvents.CLIENT_TOOLTIP_FACTORY_REGISTRATION
            .register(games.brennan.dungeontrain.client.tooltip.PrefabTooltipEvents.ModBus::onRegisterFactories);
    }

    /**
     * Client player-network handlers ({@code LoggingIn} ×3, {@code LoggingOut} ×15),
     * fired by {@code NeoForgeClientConnectionBridge}. All were NORMAL priority, not
     * cancellable, and ignore the event object; order within a tier is irrelevant.
     */
    private static void registerClientConnection() {
        // ClientPlayerNetworkEvent.LoggingIn
        DtEvents.CLIENT_LOGGING_IN
            .register(games.brennan.dungeontrain.client.snapshot.RideSnapshotDirector::onLoggingIn);
        DtEvents.CLIENT_LOGGING_IN
            .register(games.brennan.dungeontrain.client.NetworkConsentSyncClient::onLoggingIn);
        DtEvents.CLIENT_LOGGING_IN
            .register(games.brennan.dungeontrain.client.DevMessageConsentClient::onLoggingIn);

        // ClientPlayerNetworkEvent.LoggingOut
        DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.snapshot.RideSnapshotDirector::onLoggingOut);
        DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.VoidSkyEvents::onLoggingOut);
        DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.CinematicInputHandler::onLoggingOut);
        DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.CinematicPreloadGate::onLoggingOut);
        DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.NetherFogEvents::onLoggingOut);
        DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.menu.ClientStageBlocks::onLoggingOut);
        DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer::onLoggingOut);
        DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantWireframeRenderer::onLoggingOut);
        DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantLockIdRenderer::onLoggingOut);
        DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.menu.ClientPartVisibility::onLoggingOut);
        DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.menu.PrefabClientLifecycleEvents::onLoggingOut);
        DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer::onLoggingOut);
        DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.UpsideDownFogEvents::onLoggingOut);
        DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.EditorStatusHudOverlay::onLoggingOut);
        DtEvents.CLIENT_LOGGING_OUT
            .register(games.brennan.dungeontrain.client.SpawnDeckHold::onLoggingOut);
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
        DtEvents.CLIENT_TICK_PRE
            .register(games.brennan.dungeontrain.client.CinematicInputHandler::onClientTick);

        // ClientTickEvent.Post
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.BookReadClientEvents::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.CinematographerHotkeyClient.CinematographerTickWatcher::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.snapshot.RideSnapshotDirector::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.VoidSkyEvents::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.TemplateBlocksHotkeyClient.TickWatcher::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.ResumeRenderDiagnostics::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.AdvancementsHintClient::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.EditorAutoOpenHandler::onClientTickPost);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.CinematicPreloadGate::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.NetherFogEvents::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.NewWorldCommand::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.ContainerHotkeyClient.ContainerTickWatcher::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.menu.templateblocks.TemplateBlocksMenuInputHandler::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.menu.plot.EditorPlotPanelInputHandler::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuInputHandler::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.menu.plot.EditorHelpPanelInputHandler::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.menu.CommandMenuToggleHandler::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.menu.stagepanel.StagePanelMenuInputHandler::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuInputHandler::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.menu.containercontents.ContainerContentsMenuInputHandler::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.menu.parts.PartPositionMenuInputHandler::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.VariantHotkeyClient.TickWatcher::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.ManualSpawnHotkeyClient.ManualSpawnTickWatcher::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.sound.TrainSoundManager::onClientTick);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.DeveloperWelcomePopupHandler::onClientTickPost);
        DtEvents.CLIENT_TICK_POST
            .register(games.brennan.dungeontrain.client.SpawnDeckHold::onClientTick);
    }
}
