package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Central network registrar for Dungeon Train's client/server payloads.
 *
 * <p>Versioning: protocol version is a literal string. NeoForge's payload
 * handshake uses this to reject mismatched clients — bump
 * {@link #PROTOCOL_VERSION} any time packet layouts change.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DungeonTrainNet {

    public static final String PROTOCOL_VERSION = "56";

    private DungeonTrainNet() {}

    /**
     * Register all payload types. Triggered by NeoForge's mod-bus
     * {@link RegisterPayloadHandlersEvent}. IDs are stable across versions —
     * don't rename payload types, only append new ones.
     */
    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(DungeonTrain.MOD_ID).versioned(PROTOCOL_VERSION);

        registrar.playToClient(VariantHoverPacket.TYPE, VariantHoverPacket.STREAM_CODEC, VariantHoverPacket::handle);
        registrar.playToClient(CarriageIndexPacket.TYPE, CarriageIndexPacket.STREAM_CODEC, CarriageIndexPacket::handle);
        registrar.playToClient(EditorStatusPacket.TYPE, EditorStatusPacket.STREAM_CODEC, EditorStatusPacket::handle);
        registrar.playToClient(BookSuspensionSyncPacket.TYPE, BookSuspensionSyncPacket.STREAM_CODEC, BookSuspensionSyncPacket::handle);
        registrar.playToServer(VariantHotkeyPacket.TYPE, VariantHotkeyPacket.STREAM_CODEC, VariantHotkeyPacket::handle);
        registrar.playToClient(PartAssignmentSyncPacket.TYPE, PartAssignmentSyncPacket.STREAM_CODEC, PartAssignmentSyncPacket::handle);
        registrar.playToServer(PartAssignmentEditPacket.TYPE, PartAssignmentEditPacket.STREAM_CODEC, PartAssignmentEditPacket::handle);
        registrar.playToServer(PartMenuTogglePacket.TYPE, PartMenuTogglePacket.STREAM_CODEC, PartMenuTogglePacket::handle);
        registrar.playToClient(BlockVariantSyncPacket.TYPE, BlockVariantSyncPacket.STREAM_CODEC, BlockVariantSyncPacket::handle);
        registrar.playToServer(BlockVariantEditPacket.TYPE, BlockVariantEditPacket.STREAM_CODEC, BlockVariantEditPacket::handle);
        registrar.playToServer(BlockVariantMenuTogglePacket.TYPE, BlockVariantMenuTogglePacket.STREAM_CODEC, BlockVariantMenuTogglePacket::handle);
        registrar.playToServer(TemplateBlocksMenuTogglePacket.TYPE, TemplateBlocksMenuTogglePacket.STREAM_CODEC, TemplateBlocksMenuTogglePacket::handle);
        registrar.playToClient(TemplateBlocksSyncPacket.TYPE, TemplateBlocksSyncPacket.STREAM_CODEC, TemplateBlocksSyncPacket::handle);
        registrar.playToServer(TemplateBlocksEditPacket.TYPE, TemplateBlocksEditPacket.STREAM_CODEC, TemplateBlocksEditPacket::handle);
        registrar.playToClient(BlockVariantLockIdsPacket.TYPE, BlockVariantLockIdsPacket.STREAM_CODEC, BlockVariantLockIdsPacket::handle);
        registrar.playToClient(BlockVariantOutlinePacket.TYPE, BlockVariantOutlinePacket.STREAM_CODEC, BlockVariantOutlinePacket::handle);
        registrar.playToClient(EditorStrayBlocksPacket.TYPE, EditorStrayBlocksPacket.STREAM_CODEC, EditorStrayBlocksPacket::handle);
        registrar.playToClient(EditorDoorGhostsPacket.TYPE, EditorDoorGhostsPacket.STREAM_CODEC, EditorDoorGhostsPacket::handle);
        registrar.playToClient(EditorPlotLabelsPacket.TYPE, EditorPlotLabelsPacket.STREAM_CODEC, EditorPlotLabelsPacket::handle);
        registrar.playToServer(EditorPlotActionPacket.TYPE, EditorPlotActionPacket.STREAM_CODEC, EditorPlotActionPacket::handle);
        registrar.playToClient(EditorTypeMenusPacket.TYPE, EditorTypeMenusPacket.STREAM_CODEC, EditorTypeMenusPacket::handle);
        registrar.playToClient(EditorMenusModePacket.TYPE, EditorMenusModePacket.STREAM_CODEC, EditorMenusModePacket::handle);
        registrar.playToClient(CarriageGroupGapPacket.TYPE, CarriageGroupGapPacket.STREAM_CODEC, CarriageGroupGapPacket::handle);
        registrar.playToClient(CarriageNextSpawnPacket.TYPE, CarriageNextSpawnPacket.STREAM_CODEC, CarriageNextSpawnPacket::handle);
        registrar.playToClient(CarriageSpawnCollisionPacket.TYPE, CarriageSpawnCollisionPacket.STREAM_CODEC, CarriageSpawnCollisionPacket::handle);
        registrar.playToServer(ManualSpawnRequestPacket.TYPE, ManualSpawnRequestPacket.STREAM_CODEC, ManualSpawnRequestPacket::handle);
        registrar.playToClient(DebugFlagsPacket.TYPE, DebugFlagsPacket.STREAM_CODEC, DebugFlagsPacket::handle);
        registrar.playToClient(BoardingProgressPacket.TYPE, BoardingProgressPacket.STREAM_CODEC, BoardingProgressPacket::handle);
        // Carried static contents entities (End Crystals / paintings / item frames): server → client
        // hands the entity its constant plot coordinate so the client positions it from the carriage's
        // own synced sub-level pose (phase-locked, no shimmer). See TrainStaticContentsCarrier.
        registrar.playToClient(CarriedStaticEntityPacket.TYPE, CarriedStaticEntityPacket.STREAM_CODEC, CarriedStaticEntityPacket::handle);

        // Hallway portal puppets: stand-ins for the entities in the other half of a portal pair, so
        // two players either side of the midpoint can still see each other. Pushed every tick while a
        // corridor is occupied, and once empty when it clears. See portal/PortalPuppets.
        registrar.playToClient(PortalPuppetsPacket.TYPE, PortalPuppetsPacket.STREAM_CODEC, PortalPuppetsPacket::handle);
        registrar.playToClient(PortalRoomFogPacket.TYPE, PortalRoomFogPacket.STREAM_CODEC, PortalRoomFogPacket::handle);
        // …and the same region trick again for the lightmap: a room whose template asked to be lit as
        // though it stood outdoors is named to the client as a box, and the client lifts its own
        // lightmap inside it. See client/ClientPortalRoomSky.
        registrar.playToClient(PortalRoomSkyPacket.TYPE, PortalRoomSkyPacket.STREAM_CODEC, PortalRoomSkyPacket::handle);
        // …and the lightmap again for a portal CORRIDOR, which is the one portal region that cannot be
        // sent as a box: it rides a Sable sub-level, so the box the client would test moves with the
        // train every tick. The ramp itself is sent instead. See client/ClientPortalCrossing.
        registrar.playToClient(PortalCrossingPacket.TYPE, PortalCrossingPacket.STREAM_CODEC, PortalCrossingPacket::handle);
        // …and the swap itself, which the client cannot infer from the position packet that carries it:
        // the renderer has to be told to finish its occlusion rebuild before drawing, or the first
        // frames in the twin draw nothing at all. See client/portal/ClientPortalSwap.
        registrar.playToClient(PortalSwapPacket.TYPE, PortalSwapPacket.STREAM_CODEC, PortalSwapPacket::handle);
        // …and the same region trick for the engine sound: a twin corridor is not a sub-level, so the
        // client cannot work out from the train's geometry that it should still sound like one.
        registrar.playToClient(PortalTrainAudioPacket.TYPE, PortalTrainAudioPacket.STREAM_CODEC, PortalTrainAudioPacket::handle);
        // …and the swing back the other way: a puppet is not an entity, so a hit on one needs its own
        // round trip. The id is re-validated against the live pairing before anything is damaged.
        registrar.playToServer(PortalPuppetAttackPacket.TYPE, PortalPuppetAttackPacket.STREAM_CODEC, PortalPuppetAttackPacket::handle);

        registrar.playToServer(ContainerHotkeyPacket.TYPE, ContainerHotkeyPacket.STREAM_CODEC, ContainerHotkeyPacket::handle);
        registrar.playToServer(ContainerContentsMenuTogglePacket.TYPE, ContainerContentsMenuTogglePacket.STREAM_CODEC, ContainerContentsMenuTogglePacket::handle);
        registrar.playToClient(ContainerContentsSyncPacket.TYPE, ContainerContentsSyncPacket.STREAM_CODEC, ContainerContentsSyncPacket::handle);
        registrar.playToServer(ContainerContentsEditPacket.TYPE, ContainerContentsEditPacket.STREAM_CODEC, ContainerContentsEditPacket::handle);
        registrar.playToClient(PrefabRegistrySyncPacket.TYPE, PrefabRegistrySyncPacket.STREAM_CODEC, PrefabRegistrySyncPacket::handle);
        registrar.playToServer(SaveBlockVariantPrefabPacket.TYPE, SaveBlockVariantPrefabPacket.STREAM_CODEC, SaveBlockVariantPrefabPacket::handle);
        registrar.playToServer(SaveLootPrefabPacket.TYPE, SaveLootPrefabPacket.STREAM_CODEC, SaveLootPrefabPacket::handle);
        registrar.playToServer(EditorUnsavedRequestPacket.TYPE, EditorUnsavedRequestPacket.STREAM_CODEC, EditorUnsavedRequestPacket::handle);
        registrar.playToClient(EditorUnsavedListPacket.TYPE, EditorUnsavedListPacket.STREAM_CODEC, EditorUnsavedListPacket::handle);
        registrar.playToServer(EditorChangesRequestPacket.TYPE, EditorChangesRequestPacket.STREAM_CODEC, EditorChangesRequestPacket::handle);
        registrar.playToClient(EditorChangesListPacket.TYPE, EditorChangesListPacket.STREAM_CODEC, EditorChangesListPacket::handle);

        // Package menu V2 — client requests a snapshot, server pushes back with
        // package list + flags + per-package content basenames.
        registrar.playToServer(PackageListRequestPacket.TYPE, PackageListRequestPacket.STREAM_CODEC, PackageListRequestPacket::handle);
        registrar.playToClient(PackageListSyncPacket.TYPE, PackageListSyncPacket.STREAM_CODEC, PackageListSyncPacket::handle);

        // Starting-book close-detection: client ScreenEvent.Closing → server burn flow.
        registrar.playToServer(StartingBookClosedPacket.TYPE, StartingBookClosedPacket.STREAM_CODEC, StartingBookClosedPacket::handle);

        // Book-read telemetry: client measures a book read (open→close, per-page timing) and sends it on
        // close; server consent-gates + enriches narrative fields + reports to the relay's Books explorer.
        registrar.playToServer(BookReadClosedPacket.TYPE, BookReadClosedPacket.STREAM_CODEC, BookReadClosedPacket::handle);

        // Client-only actions the server can't see (currently: the train engine volume setting
        // changing). Allowlisted server-side — see ClientActionPacket.
        registrar.playToServer(ClientActionPacket.TYPE, ClientActionPacket.STREAM_CODEC, ClientActionPacket::handle);

        // Book vote: client casts 👍/👎 from the virtual vote page (buttons or Y/N hotkeys); server
        // re-validates the held stack's identity, stamps dt_book_vote (offline burn color), and
        // consent-gates the relay POST.
        registrar.playToServer(BookVotePacket.TYPE, BookVotePacket.STREAM_CODEC, BookVotePacket::handle);

        // Book report: client asks for a community book to be pulled from the shared pool (the ⚠
        // control on the same vote page); server re-validates the held stack, stamps
        // dt_book_reported, and consent-gates the relay POST. Shared books only.
        registrar.playToServer(BookReportPacket.TYPE, BookReportPacket.STREAM_CODEC, BookReportPacket::handle);
        // The author-only siblings of Report — see BookVoteClientEvents for which book gets which.
        registrar.playToServer(BookProtestPacket.TYPE, BookProtestPacket.STREAM_CODEC, BookProtestPacket::handle);
        registrar.playToServer(BookPrivatePacket.TYPE, BookPrivatePacket.STREAM_CODEC, BookPrivatePacket::handle);

        // Lectern letters: server → client to open the book sign screen when a book & quill is
        // right-clicked onto a lectern and the feature is active; client → server when that screen is
        // closed WITHOUT signing, so the server leaves the unsigned book on the lectern as a draft.
        registrar.playToClient(OpenLetterEditorPacket.TYPE, OpenLetterEditorPacket.STREAM_CODEC, OpenLetterEditorPacket::handle);
        registrar.playToServer(LetterDraftToLecternPacket.TYPE, LetterDraftToLecternPacket.STREAM_CODEC, LetterDraftToLecternPacket::handle);

        // Mod recommendation: the death screen's Mod Recommendations page sends one mod + comment per
        // submit (or a typed name, for a mod the player doesn't have); server consent-gates, posts it
        // to the Discord survey channel and queues a text-free telemetry event.
        registrar.playToServer(ModRecommendPacket.TYPE, ModRecommendPacket.STREAM_CODEC, ModRecommendPacket::handle);

        // Death-screen run-stats snapshot, server → dying player on LivingDeathEvent.
        registrar.playToClient(DeathStatsPacket.TYPE, DeathStatsPacket.STREAM_CODEC, DeathStatsPacket::handle);
        // Scenic ride photo for the top-level death report, client → server when the death screen opens.
        registrar.playToServer(DeathPhotoPacket.TYPE, DeathPhotoPacket.STREAM_CODEC, DeathPhotoPacket::handle);
        // Full tagged ride-photo gallery, client → server when the death screen opens; the server
        // archives every photo (+ its facets) to the relay's Photos page via ShotUploadClient.
        registrar.playToServer(RideGalleryPacket.TYPE, RideGalleryPacket.STREAM_CODEC, RideGalleryPacket::handle);
        // Ride-photo cue, server → client: a moment only the server can see (a drifting carriage
        // being changed, arriving in a Train Dimension) that the client should try to photograph.
        registrar.playToClient(SnapshotCuePacket.TYPE, SnapshotCuePacket.STREAM_CODEC, SnapshotCuePacket::handle);
        // Bug-report logs: client → server when the player reports a bug on the death-screen survey;
        // archived under logs/<version>/<user>/ and posted as Discord attachments to the feedback feed.
        registrar.playToServer(BugReportLogsPacket.TYPE, BugReportLogsPacket.STREAM_CODEC, BugReportLogsPacket::handle);

        // Spawn intro cinematic: server → joining player to start it; client → server when it ends.
        registrar.playToClient(CinematicIntroPacket.TYPE, CinematicIntroPacket.STREAM_CODEC, CinematicIntroPacket::handle);
        registrar.playToClient(CinematicPreloadBeginPacket.TYPE, CinematicPreloadBeginPacket.STREAM_CODEC, CinematicPreloadBeginPacket::handle);
        registrar.playToServer(CinematicDonePacket.TYPE, CinematicDonePacket.STREAM_CODEC, CinematicDonePacket::handle);

        // On-train spawn deck-hold: server → joining/respawning player to keep
        // the client from free-falling off the deck during the spawn-storm stall.
        registrar.playToClient(SpawnDeckHoldPacket.TYPE, SpawnDeckHoldPacket.STREAM_CODEC, SpawnDeckHoldPacket::handle);
        registrar.playToClient(PortalTestSessionPacket.TYPE, PortalTestSessionPacket.STREAM_CODEC, PortalTestSessionPacket::handle);

        // Advancements keybind hint: server → the earning player on a gameplay
        // advancement. The client decides whether to show it (gated on its local
        // "opened advancements" flag) and renders it with the live keybind.
        registrar.playToClient(AdvancementsHintPacket.TYPE, AdvancementsHintPacket.STREAM_CODEC, AdvancementsHintPacket::handle);

        // Free Play confirmation: server holds a tainting action (creative/spectator
        // switch or cheat command) and asks before it commits; client replies
        // confirmed/canceled (the "don't show again" pref lives client-side).
        registrar.playToClient(ShowFreePlayConfirmPacket.TYPE, ShowFreePlayConfirmPacket.STREAM_CODEC, ShowFreePlayConfirmPacket::handle);
        registrar.playToServer(FreePlayConfirmResponsePacket.TYPE, FreePlayConfirmResponsePacket.STREAM_CODEC, FreePlayConfirmResponsePacket::handle);
        registrar.playToClient(ShowCustomContentPromptPacket.TYPE, ShowCustomContentPromptPacket.STREAM_CODEC, ShowCustomContentPromptPacket::handle);
        registrar.playToServer(CustomContentChoicePacket.TYPE, CustomContentChoicePacket.STREAM_CODEC, CustomContentChoicePacket::handle);
        registrar.playToClient(ShowDifficultyConfirmPacket.TYPE, ShowDifficultyConfirmPacket.STREAM_CODEC, ShowDifficultyConfirmPacket::handle);
        registrar.playToServer(DifficultyConfirmResponsePacket.TYPE, DifficultyConfirmResponsePacket.STREAM_CODEC, DifficultyConfirmResponsePacket::handle);

        // Pause-menu "Abandon This Run": client → server kill request, ending the run via the death screen.
        registrar.playToServer(AbandonRunPacket.TYPE, AbandonRunPacket.STREAM_CODEC, AbandonRunPacket::handle);
        registrar.playToServer(BuilderSetupPacket.TYPE, BuilderSetupPacket.STREAM_CODEC, BuilderSetupPacket::handle);
        registrar.playToClient(BuilderBoundsPacket.TYPE, BuilderBoundsPacket.STREAM_CODEC, BuilderBoundsPacket::handle);
        registrar.playToServer(BuilderSwitchPacket.TYPE, BuilderSwitchPacket.STREAM_CODEC, BuilderSwitchPacket::handle);
        registrar.playToServer(BuilderDirtyRequestPacket.TYPE, BuilderDirtyRequestPacket.STREAM_CODEC, BuilderDirtyRequestPacket::handle);
        registrar.playToServer(BuilderRoomSizePacket.TYPE, BuilderRoomSizePacket.STREAM_CODEC, BuilderRoomSizePacket::handle);
        registrar.playToServer(BuilderStructureModePacket.TYPE, BuilderStructureModePacket.STREAM_CODEC, BuilderStructureModePacket::handle);
        registrar.playToServer(BuilderStructureRefreshPacket.TYPE, BuilderStructureRefreshPacket.STREAM_CODEC, BuilderStructureRefreshPacket::handle);
        registrar.playToClient(BuilderDirtyPacket.TYPE, BuilderDirtyPacket.STREAM_CODEC, BuilderDirtyPacket::handle);
        registrar.playToServer(BuilderSavePacket.TYPE, BuilderSavePacket.STREAM_CODEC, BuilderSavePacket::handle);
        registrar.playToServer(BuilderNewPacket.TYPE, BuilderNewPacket.STREAM_CODEC, BuilderNewPacket::handle);
        registrar.playToServer(BuilderOpenPacket.TYPE, BuilderOpenPacket.STREAM_CODEC, BuilderOpenPacket::handle);
        registrar.playToServer(BuilderRenamePacket.TYPE, BuilderRenamePacket.STREAM_CODEC, BuilderRenamePacket::handle);
        registrar.playToClient(BuilderPhotoPacket.TYPE, BuilderPhotoPacket.STREAM_CODEC, BuilderPhotoPacket::handle);
        registrar.playToClient(BuilderCinematicPacket.TYPE, BuilderCinematicPacket.STREAM_CODEC, BuilderCinematicPacket::handle);

        // Train Builder profile ("My Builds"): the client asks, the server fetches from the relay and
        // pushes the list back; the action packet publishes one build to the train or withdraws it.
        // The relay client is server-side, so the screen can only ever ask through here.
        registrar.playToServer(BuilderProfileRequestPacket.TYPE, BuilderProfileRequestPacket.STREAM_CODEC, BuilderProfileRequestPacket::handle);
        registrar.playToClient(BuilderProfilePacket.TYPE, BuilderProfilePacket.STREAM_CODEC, BuilderProfilePacket::handle);
        registrar.playToServer(BuilderProfileActionPacket.TYPE, BuilderProfileActionPacket.STREAM_CODEC, BuilderProfileActionPacket::handle);

        // Remote-echo encounter screenshot: server → player at first eye-contact to frame + capture the
        // echo; client → server with the resulting PNG, buffered on the encounter journal for its story embed.
        registrar.playToClient(CaptureEchoPacket.TYPE, CaptureEchoPacket.STREAM_CODEC, CaptureEchoPacket::handle);
        registrar.playToServer(EchoPhotoPacket.TYPE, EchoPhotoPacket.STREAM_CODEC, EchoPhotoPacket::handle);

        // Developer-message consent: client → server login sync of persisted consent state;
        // server → client push when consent is granted in-game so the client persists it.
        registrar.playToServer(ConsentSyncPacket.TYPE, ConsentSyncPacket.STREAM_CODEC, ConsentSyncPacket::handle);
        registrar.playToClient(ConsentUpdatePacket.TYPE, ConsentUpdatePacket.STREAM_CODEC, ConsentUpdatePacket::handle);

        // Network-access consent (community shared books): client → server login sync of the player's
        // Discord Presence "use the internet?" consent, so the server can gate book uploads.
        registrar.playToServer(NetworkConsentSyncPacket.TYPE, NetworkConsentSyncPacket.STREAM_CODEC, NetworkConsentSyncPacket::handle);
        registrar.playToServer(ContentModeSyncPacket.TYPE, ContentModeSyncPacket.STREAM_CODEC, ContentModeSyncPacket::handle);

        // Political Filter (community content): client → server login sync (+ on change) of the
        // player's preference, so per-player book selection can withhold politically-tagged content.
        registrar.playToServer(PoliticalFilterSyncPacket.TYPE, PoliticalFilterSyncPacket.STREAM_CODEC, PoliticalFilterSyncPacket::handle);

        // Community shared-book read history: client → server login sync (+ per-read top-ups) of the
        // player's GLOBAL client-side read set, the fallback source for the loot selector's unread-first
        // when the relay can't personalise the pool. NOT consent-gated — carries only public pool ids.
        registrar.playToServer(SharedBookReadSyncPacket.TYPE, SharedBookReadSyncPacket.STREAM_CODEC, SharedBookReadSyncPacket::handle);

        // World disintegration band: server → joining player with the per-world
        // carriage length + train flag, so the client can fade the sky/fog toward
        // the End look across the band.
        registrar.playToClient(VoidBandSyncPacket.TYPE, VoidBandSyncPacket.STREAM_CODEC, VoidBandSyncPacket::handle);

        // Stage Blocks panel: per-stage row icon strips for the Stages panel (S2C, own channel —
        // pushed only when StageBlockIndex.generation() moves), the panel detail sync (S2C), and
        // the panel ops (C2S: open/close/replace/hide-unused).
        registrar.playToClient(StageBlockStripsPacket.TYPE, StageBlockStripsPacket.STREAM_CODEC, StageBlockStripsPacket::handle);
        registrar.playToClient(StageBlocksSyncPacket.TYPE, StageBlocksSyncPacket.STREAM_CODEC, StageBlocksSyncPacket::handle);
        registrar.playToServer(StagePanelEditPacket.TYPE, StagePanelEditPacket.STREAM_CODEC, StagePanelEditPacket::handle);

        // Per-part editor-grid visibility (hidden set) — S2C mirror for the part-list ☑/☐ glyphs.
        registrar.playToClient(PartVisibilityPacket.TYPE, PartVisibilityPacket.STREAM_CODEC, PartVisibilityPacket::handle);

        // Editor middle-click: copy the looked-at cell's variants into the hotbar (C2S).
        registrar.playToServer(BlockVariantCopyPickPacket.TYPE, BlockVariantCopyPickPacket.STREAM_CODEC, BlockVariantCopyPickPacket::handle);

        // Why this run is in Free Play, server → client, pushed whenever the badge goes on or off.
        // Feeds the effect's hover tooltip — see FreePlayCausePacket.
        registrar.playToClient(FreePlayCausePacket.TYPE, FreePlayCausePacket.STREAM_CODEC, FreePlayCausePacket::handle);
    }

    /** Convenience: send a payload to the server (client → server). */
    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    /** Convenience: send a payload to a single player. */
    public static void sendTo(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }
}
