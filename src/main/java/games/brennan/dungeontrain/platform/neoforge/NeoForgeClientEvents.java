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
