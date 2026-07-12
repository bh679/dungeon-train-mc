package games.brennan.dungeontrain.net;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.net.platform.DtPayloadContext;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Declarative, loader-neutral table of every Dungeon Train network payload:
 * direction, wire type, codec, and handler. {@link DungeonTrainNet} is the
 * only thing that knows about NeoForge's {@code PayloadRegistrar} — it just
 * iterates {@link #ALL} in order and registers each one.
 *
 * <p>Order and direction here are load-bearing: they mirror the exact
 * sequence {@code DungeonTrainNet.register} used to call
 * {@code registrar.playToClient}/{@code playToServer} before this table
 * existed. Don't reorder or re-direction existing entries — packet ids are
 * stable across versions (see {@link DungeonTrainNet#PROTOCOL_VERSION}); only
 * append new ones.</p>
 *
 * <p>This table lives alongside the packet classes in the root {@code net}
 * package rather than in {@code :common}, because most handlers still reach
 * into root/editor/client state that hasn't migrated to {@code :common} yet
 * (only the DtCore.MOD_ID coupling was removed, via {@code DtModId}).
 * Once enough of those dependencies move, individual packet classes — and
 * eventually this table — can migrate too.</p>
 */
public final class DtPayloads {

    /** Wire direction, independent of any loader's registration API. */
    public enum Direction { S2C, C2S }

    /** One payload's full registration: direction + wire type + codec + handler. */
    public record Spec<T extends CustomPacketPayload>(
        Direction direction,
        CustomPacketPayload.Type<T> type,
        StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
        BiConsumer<T, DtPayloadContext> handler
    ) {}

    private DtPayloads() {}

    private static <T extends CustomPacketPayload> Spec<T> spec(
            Direction direction,
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, DtPayloadContext> handler) {
        return new Spec<>(direction, type, codec, handler);
    }

    public static final List<Spec<?>> ALL = List.of(
        spec(Direction.S2C, VariantHoverPacket.TYPE, VariantHoverPacket.STREAM_CODEC, VariantHoverPacket::handle),
        spec(Direction.S2C, CarriageIndexPacket.TYPE, CarriageIndexPacket.STREAM_CODEC, CarriageIndexPacket::handle),
        spec(Direction.S2C, EditorStatusPacket.TYPE, EditorStatusPacket.STREAM_CODEC, EditorStatusPacket::handle),
        spec(Direction.C2S, VariantHotkeyPacket.TYPE, VariantHotkeyPacket.STREAM_CODEC, VariantHotkeyPacket::handle),
        spec(Direction.S2C, PartAssignmentSyncPacket.TYPE, PartAssignmentSyncPacket.STREAM_CODEC, PartAssignmentSyncPacket::handle),
        spec(Direction.C2S, PartAssignmentEditPacket.TYPE, PartAssignmentEditPacket.STREAM_CODEC, PartAssignmentEditPacket::handle),
        spec(Direction.C2S, PartMenuTogglePacket.TYPE, PartMenuTogglePacket.STREAM_CODEC, PartMenuTogglePacket::handle),
        spec(Direction.S2C, BlockVariantSyncPacket.TYPE, BlockVariantSyncPacket.STREAM_CODEC, BlockVariantSyncPacket::handle),
        spec(Direction.C2S, BlockVariantEditPacket.TYPE, BlockVariantEditPacket.STREAM_CODEC, BlockVariantEditPacket::handle),
        spec(Direction.C2S, BlockVariantMenuTogglePacket.TYPE, BlockVariantMenuTogglePacket.STREAM_CODEC, BlockVariantMenuTogglePacket::handle),
        spec(Direction.C2S, TemplateBlocksMenuTogglePacket.TYPE, TemplateBlocksMenuTogglePacket.STREAM_CODEC, TemplateBlocksMenuTogglePacket::handle),
        spec(Direction.S2C, TemplateBlocksSyncPacket.TYPE, TemplateBlocksSyncPacket.STREAM_CODEC, TemplateBlocksSyncPacket::handle),
        spec(Direction.C2S, TemplateBlocksEditPacket.TYPE, TemplateBlocksEditPacket.STREAM_CODEC, TemplateBlocksEditPacket::handle),
        spec(Direction.S2C, BlockVariantLockIdsPacket.TYPE, BlockVariantLockIdsPacket.STREAM_CODEC, BlockVariantLockIdsPacket::handle),
        spec(Direction.S2C, BlockVariantOutlinePacket.TYPE, BlockVariantOutlinePacket.STREAM_CODEC, BlockVariantOutlinePacket::handle),
        spec(Direction.S2C, EditorPlotLabelsPacket.TYPE, EditorPlotLabelsPacket.STREAM_CODEC, EditorPlotLabelsPacket::handle),
        spec(Direction.C2S, EditorPlotActionPacket.TYPE, EditorPlotActionPacket.STREAM_CODEC, EditorPlotActionPacket::handle),
        spec(Direction.S2C, EditorTypeMenusPacket.TYPE, EditorTypeMenusPacket.STREAM_CODEC, EditorTypeMenusPacket::handle),
        spec(Direction.S2C, CarriageGroupGapPacket.TYPE, CarriageGroupGapPacket.STREAM_CODEC, CarriageGroupGapPacket::handle),
        spec(Direction.S2C, CarriageNextSpawnPacket.TYPE, CarriageNextSpawnPacket.STREAM_CODEC, CarriageNextSpawnPacket::handle),
        spec(Direction.S2C, CarriageSpawnCollisionPacket.TYPE, CarriageSpawnCollisionPacket.STREAM_CODEC, CarriageSpawnCollisionPacket::handle),
        spec(Direction.C2S, ManualSpawnRequestPacket.TYPE, ManualSpawnRequestPacket.STREAM_CODEC, ManualSpawnRequestPacket::handle),
        spec(Direction.S2C, DebugFlagsPacket.TYPE, DebugFlagsPacket.STREAM_CODEC, DebugFlagsPacket::handle),
        spec(Direction.S2C, BoardingProgressPacket.TYPE, BoardingProgressPacket.STREAM_CODEC, BoardingProgressPacket::handle),

        spec(Direction.C2S, ContainerHotkeyPacket.TYPE, ContainerHotkeyPacket.STREAM_CODEC, ContainerHotkeyPacket::handle),
        spec(Direction.C2S, ContainerContentsMenuTogglePacket.TYPE, ContainerContentsMenuTogglePacket.STREAM_CODEC, ContainerContentsMenuTogglePacket::handle),
        spec(Direction.S2C, ContainerContentsSyncPacket.TYPE, ContainerContentsSyncPacket.STREAM_CODEC, ContainerContentsSyncPacket::handle),
        spec(Direction.C2S, ContainerContentsEditPacket.TYPE, ContainerContentsEditPacket.STREAM_CODEC, ContainerContentsEditPacket::handle),
        spec(Direction.S2C, PrefabRegistrySyncPacket.TYPE, PrefabRegistrySyncPacket.STREAM_CODEC, PrefabRegistrySyncPacket::handle),
        spec(Direction.C2S, SaveBlockVariantPrefabPacket.TYPE, SaveBlockVariantPrefabPacket.STREAM_CODEC, SaveBlockVariantPrefabPacket::handle),
        spec(Direction.C2S, SaveLootPrefabPacket.TYPE, SaveLootPrefabPacket.STREAM_CODEC, SaveLootPrefabPacket::handle),
        spec(Direction.C2S, EditorUnsavedRequestPacket.TYPE, EditorUnsavedRequestPacket.STREAM_CODEC, EditorUnsavedRequestPacket::handle),
        spec(Direction.S2C, EditorUnsavedListPacket.TYPE, EditorUnsavedListPacket.STREAM_CODEC, EditorUnsavedListPacket::handle),
        spec(Direction.C2S, EditorChangesRequestPacket.TYPE, EditorChangesRequestPacket.STREAM_CODEC, EditorChangesRequestPacket::handle),
        spec(Direction.S2C, EditorChangesListPacket.TYPE, EditorChangesListPacket.STREAM_CODEC, EditorChangesListPacket::handle),

        // Package menu V2 — client requests a snapshot, server pushes back with
        // package list + flags + per-package content basenames.
        spec(Direction.C2S, PackageListRequestPacket.TYPE, PackageListRequestPacket.STREAM_CODEC, PackageListRequestPacket::handle),
        spec(Direction.S2C, PackageListSyncPacket.TYPE, PackageListSyncPacket.STREAM_CODEC, PackageListSyncPacket::handle),

        // Starting-book close-detection: client ScreenEvent.Closing → server burn flow.
        spec(Direction.C2S, StartingBookClosedPacket.TYPE, StartingBookClosedPacket.STREAM_CODEC, StartingBookClosedPacket::handle),

        // Book-read telemetry: client measures a book read (open→close, per-page timing) and sends it on
        // close; server consent-gates + enriches narrative fields + reports to the relay's Books explorer.
        spec(Direction.C2S, BookReadClosedPacket.TYPE, BookReadClosedPacket.STREAM_CODEC, BookReadClosedPacket::handle),

        // Lectern letters: server → client to open the book sign screen when a book & quill is
        // right-clicked onto a lectern and the feature is active; client → server when that screen is
        // closed WITHOUT signing, so the server leaves the unsigned book on the lectern as a draft.
        spec(Direction.S2C, OpenLetterEditorPacket.TYPE, OpenLetterEditorPacket.STREAM_CODEC, OpenLetterEditorPacket::handle),
        spec(Direction.C2S, LetterDraftToLecternPacket.TYPE, LetterDraftToLecternPacket.STREAM_CODEC, LetterDraftToLecternPacket::handle),

        // Death-screen run-stats snapshot, server → dying player on LivingDeathEvent.
        spec(Direction.S2C, DeathStatsPacket.TYPE, DeathStatsPacket.STREAM_CODEC, DeathStatsPacket::handle),
        // Scenic ride photo for the top-level death report, client → server when the death screen opens.
        spec(Direction.C2S, DeathPhotoPacket.TYPE, DeathPhotoPacket.STREAM_CODEC, DeathPhotoPacket::handle),
        // Bug-report logs: client → server when the player reports a bug on the death-screen survey;
        // archived under logs/<version>/<user>/ and posted as Discord attachments to the feedback feed.
        spec(Direction.C2S, BugReportLogsPacket.TYPE, BugReportLogsPacket.STREAM_CODEC, BugReportLogsPacket::handle),

        // Spawn intro cinematic: server → joining player to start it; client → server when it ends.
        spec(Direction.S2C, CinematicIntroPacket.TYPE, CinematicIntroPacket.STREAM_CODEC, CinematicIntroPacket::handle),
        spec(Direction.S2C, CinematicPreloadBeginPacket.TYPE, CinematicPreloadBeginPacket.STREAM_CODEC, CinematicPreloadBeginPacket::handle),
        spec(Direction.C2S, CinematicDonePacket.TYPE, CinematicDonePacket.STREAM_CODEC, CinematicDonePacket::handle),

        // On-train spawn deck-hold: server → joining/respawning player to keep
        // the client from free-falling off the deck during the spawn-storm stall.
        spec(Direction.S2C, SpawnDeckHoldPacket.TYPE, SpawnDeckHoldPacket.STREAM_CODEC, SpawnDeckHoldPacket::handle),

        // Advancements keybind hint: server → the earning player on a gameplay
        // advancement. The client decides whether to show it (gated on its local
        // "opened advancements" flag) and renders it with the live keybind.
        spec(Direction.S2C, AdvancementsHintPacket.TYPE, AdvancementsHintPacket.STREAM_CODEC, AdvancementsHintPacket::handle),

        // Free Play confirmation: server holds a tainting action (creative/spectator
        // switch or cheat command) and asks before it commits; client replies
        // confirmed/canceled (the "don't show again" pref lives client-side).
        spec(Direction.S2C, ShowFreePlayConfirmPacket.TYPE, ShowFreePlayConfirmPacket.STREAM_CODEC, ShowFreePlayConfirmPacket::handle),
        spec(Direction.C2S, FreePlayConfirmResponsePacket.TYPE, FreePlayConfirmResponsePacket.STREAM_CODEC, FreePlayConfirmResponsePacket::handle),

        // Pause-menu "Abandon This Run": client → server kill request, ending the run via the death screen.
        spec(Direction.C2S, AbandonRunPacket.TYPE, AbandonRunPacket.STREAM_CODEC, AbandonRunPacket::handle),

        // Remote-echo encounter screenshot: server → player at first eye-contact to frame + capture the
        // echo; client → server with the resulting PNG, buffered on the encounter journal for its story embed.
        spec(Direction.S2C, CaptureEchoPacket.TYPE, CaptureEchoPacket.STREAM_CODEC, CaptureEchoPacket::handle),
        spec(Direction.C2S, EchoPhotoPacket.TYPE, EchoPhotoPacket.STREAM_CODEC, EchoPhotoPacket::handle),

        // Developer-message consent: client → server login sync of persisted consent state;
        // server → client push when consent is granted in-game so the client persists it.
        spec(Direction.C2S, ConsentSyncPacket.TYPE, ConsentSyncPacket.STREAM_CODEC, ConsentSyncPacket::handle),
        spec(Direction.S2C, ConsentUpdatePacket.TYPE, ConsentUpdatePacket.STREAM_CODEC, ConsentUpdatePacket::handle),

        // Network-access consent (community shared books): client → server login sync of the player's
        // Discord Presence "use the internet?" consent, so the server can gate book uploads.
        spec(Direction.C2S, NetworkConsentSyncPacket.TYPE, NetworkConsentSyncPacket.STREAM_CODEC, NetworkConsentSyncPacket::handle),

        // World disintegration band: server → joining player with the per-world
        // carriage length + train flag, so the client can fade the sky/fog toward
        // the End look across the band.
        spec(Direction.S2C, VoidBandSyncPacket.TYPE, VoidBandSyncPacket.STREAM_CODEC, VoidBandSyncPacket::handle),

        // Stage Blocks panel: per-stage row icon strips for the Stages panel (S2C, own channel —
        // pushed only when StageBlockIndex.generation() moves), the panel detail sync (S2C), and
        // the panel ops (C2S: open/close/replace/hide-unused).
        spec(Direction.S2C, StageBlockStripsPacket.TYPE, StageBlockStripsPacket.STREAM_CODEC, StageBlockStripsPacket::handle),
        spec(Direction.S2C, StageBlocksSyncPacket.TYPE, StageBlocksSyncPacket.STREAM_CODEC, StageBlocksSyncPacket::handle),
        spec(Direction.C2S, StagePanelEditPacket.TYPE, StagePanelEditPacket.STREAM_CODEC, StagePanelEditPacket::handle),

        // Per-part editor-grid visibility (hidden set) — S2C mirror for the part-list ☑/☐ glyphs.
        spec(Direction.S2C, PartVisibilityPacket.TYPE, PartVisibilityPacket.STREAM_CODEC, PartVisibilityPacket::handle)
    );
}
