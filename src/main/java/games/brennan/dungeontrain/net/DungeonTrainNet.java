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

    public static final String PROTOCOL_VERSION = "36";

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
        registrar.playToServer(VariantHotkeyPacket.TYPE, VariantHotkeyPacket.STREAM_CODEC, VariantHotkeyPacket::handle);
        registrar.playToClient(PartAssignmentSyncPacket.TYPE, PartAssignmentSyncPacket.STREAM_CODEC, PartAssignmentSyncPacket::handle);
        registrar.playToServer(PartAssignmentEditPacket.TYPE, PartAssignmentEditPacket.STREAM_CODEC, PartAssignmentEditPacket::handle);
        registrar.playToServer(PartMenuTogglePacket.TYPE, PartMenuTogglePacket.STREAM_CODEC, PartMenuTogglePacket::handle);
        registrar.playToClient(BlockVariantSyncPacket.TYPE, BlockVariantSyncPacket.STREAM_CODEC, BlockVariantSyncPacket::handle);
        registrar.playToServer(BlockVariantEditPacket.TYPE, BlockVariantEditPacket.STREAM_CODEC, BlockVariantEditPacket::handle);
        registrar.playToServer(BlockVariantMenuTogglePacket.TYPE, BlockVariantMenuTogglePacket.STREAM_CODEC, BlockVariantMenuTogglePacket::handle);
        registrar.playToClient(BlockVariantLockIdsPacket.TYPE, BlockVariantLockIdsPacket.STREAM_CODEC, BlockVariantLockIdsPacket::handle);
        registrar.playToClient(BlockVariantOutlinePacket.TYPE, BlockVariantOutlinePacket.STREAM_CODEC, BlockVariantOutlinePacket::handle);
        registrar.playToClient(EditorPlotLabelsPacket.TYPE, EditorPlotLabelsPacket.STREAM_CODEC, EditorPlotLabelsPacket::handle);
        registrar.playToServer(EditorPlotActionPacket.TYPE, EditorPlotActionPacket.STREAM_CODEC, EditorPlotActionPacket::handle);
        registrar.playToClient(EditorTypeMenusPacket.TYPE, EditorTypeMenusPacket.STREAM_CODEC, EditorTypeMenusPacket::handle);
        registrar.playToClient(CarriageGroupGapPacket.TYPE, CarriageGroupGapPacket.STREAM_CODEC, CarriageGroupGapPacket::handle);
        registrar.playToClient(CarriageNextSpawnPacket.TYPE, CarriageNextSpawnPacket.STREAM_CODEC, CarriageNextSpawnPacket::handle);
        registrar.playToClient(CarriageSpawnCollisionPacket.TYPE, CarriageSpawnCollisionPacket.STREAM_CODEC, CarriageSpawnCollisionPacket::handle);
        registrar.playToServer(ManualSpawnRequestPacket.TYPE, ManualSpawnRequestPacket.STREAM_CODEC, ManualSpawnRequestPacket::handle);
        registrar.playToClient(DebugFlagsPacket.TYPE, DebugFlagsPacket.STREAM_CODEC, DebugFlagsPacket::handle);
        registrar.playToClient(BoardingProgressPacket.TYPE, BoardingProgressPacket.STREAM_CODEC, BoardingProgressPacket::handle);

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

        // Death-screen run-stats snapshot, server → dying player on LivingDeathEvent.
        registrar.playToClient(DeathStatsPacket.TYPE, DeathStatsPacket.STREAM_CODEC, DeathStatsPacket::handle);
        // Scenic ride photo for the top-level death report, client → server when the death screen opens.
        registrar.playToServer(DeathPhotoPacket.TYPE, DeathPhotoPacket.STREAM_CODEC, DeathPhotoPacket::handle);
        // Bug-report logs: client → server when the player reports a bug on the death-screen survey;
        // archived under logs/<version>/<user>/ and posted as Discord attachments to the feedback feed.
        registrar.playToServer(BugReportLogsPacket.TYPE, BugReportLogsPacket.STREAM_CODEC, BugReportLogsPacket::handle);

        // Spawn intro cinematic: server → joining player to start it; client → server when it ends.
        registrar.playToClient(CinematicIntroPacket.TYPE, CinematicIntroPacket.STREAM_CODEC, CinematicIntroPacket::handle);
        registrar.playToServer(CinematicDonePacket.TYPE, CinematicDonePacket.STREAM_CODEC, CinematicDonePacket::handle);

        // On-train spawn deck-hold: server → joining/respawning player to keep
        // the client from free-falling off the deck during the spawn-storm stall.
        registrar.playToClient(SpawnDeckHoldPacket.TYPE, SpawnDeckHoldPacket.STREAM_CODEC, SpawnDeckHoldPacket::handle);

        // Advancements keybind hint: server → the earning player on a gameplay
        // advancement. The client decides whether to show it (gated on its local
        // "opened advancements" flag) and renders it with the live keybind.
        registrar.playToClient(AdvancementsHintPacket.TYPE, AdvancementsHintPacket.STREAM_CODEC, AdvancementsHintPacket::handle);

        // Free Play confirmation: server holds a tainting action (creative/spectator
        // switch or cheat command) and asks before it commits; client replies
        // confirmed/canceled (the "don't show again" pref lives client-side).
        registrar.playToClient(ShowFreePlayConfirmPacket.TYPE, ShowFreePlayConfirmPacket.STREAM_CODEC, ShowFreePlayConfirmPacket::handle);
        registrar.playToServer(FreePlayConfirmResponsePacket.TYPE, FreePlayConfirmResponsePacket.STREAM_CODEC, FreePlayConfirmResponsePacket::handle);

        // Pause-menu "Abandon This Run": client → server kill request, ending the run via the death screen.
        registrar.playToServer(AbandonRunPacket.TYPE, AbandonRunPacket.STREAM_CODEC, AbandonRunPacket::handle);

        // Remote-echo encounter screenshot: server → player at first eye-contact to frame + capture the
        // echo; client → server with the resulting PNG, buffered on the encounter journal for its story embed.
        registrar.playToClient(CaptureEchoPacket.TYPE, CaptureEchoPacket.STREAM_CODEC, CaptureEchoPacket::handle);
        registrar.playToServer(EchoPhotoPacket.TYPE, EchoPhotoPacket.STREAM_CODEC, EchoPhotoPacket::handle);

        // Developer-message consent: client → server login sync of persisted consent state;
        // server → client push when consent is granted in-game so the client persists it.
        registrar.playToServer(ConsentSyncPacket.TYPE, ConsentSyncPacket.STREAM_CODEC, ConsentSyncPacket::handle);
        registrar.playToClient(ConsentUpdatePacket.TYPE, ConsentUpdatePacket.STREAM_CODEC, ConsentUpdatePacket::handle);

        // World disintegration band: server → joining player with the per-world
        // carriage length + train flag, so the client can fade the sky/fog toward
        // the End look across the band.
        registrar.playToClient(VoidBandSyncPacket.TYPE, VoidBandSyncPacket.STREAM_CODEC, VoidBandSyncPacket::handle);
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
