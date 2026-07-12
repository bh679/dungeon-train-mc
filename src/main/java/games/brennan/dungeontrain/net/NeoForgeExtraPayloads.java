package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.net.DtPayloads.Direction;
import games.brennan.dungeontrain.net.DtPayloads.Spec;

import java.util.List;

import static games.brennan.dungeontrain.net.DtPayloads.spec;

/**
 * Root-resident remainder of the Dungeon Train payload table — the specs whose
 * packet classes still can't compile in {@code :common} because they reach into
 * root/editor/client state (the editor cluster, cinematic / consent / echo
 * packets, bug-report + package menus). Companion to the {@code :common}-resident
 * {@link DtPayloads#ALL}; {@link DungeonTrainNet} registers both tables.
 *
 * <p><b>Why these are root:</b> each entry's packet class depends on a
 * root-only symbol — editor stores / renderers ({@code EditorStatusPacket},
 * {@code PartAssignment*}, {@code BlockVariant*Edit/Menu/LockIds},
 * {@code TemplateBlocks*Edit/Menu}, {@code EditorPlot*}, {@code EditorType*},
 * {@code Editor{Unsaved,Changes}*}, {@code Prefab*}, {@code Save*Prefab},
 * {@code Package*}, {@code Container*Edit/Menu}, {@code StagePanelEdit}), the
 * carriage/train core ({@code CarriageIndexPacket}, {@code BoardingProgress},
 * {@code ManualSpawnRequest}), client-only screens/state ({@code Cinematic*},
 * {@code ShowFreePlayConfirm}/{@code FreePlayConfirmResponse}, {@code Consent*},
 * {@code CaptureEcho}/{@code EchoPhoto}, {@code Starting/BookReadClosed},
 * {@code LetterDraftToLectern}, {@code BugReportLogs}). They migrate to
 * {@link DtPayloads} class-by-class as their dependencies move to {@code :common}.
 *
 * <p>Order is preserved from the original single combined table for readability.
 * Registration order carries no protocol meaning — payloads are keyed by their
 * {@code CustomPacketPayload.Type} id (see {@link DtPayloads}).</p>
 */
public final class NeoForgeExtraPayloads {

    private NeoForgeExtraPayloads() {}

    /** The root-resident payloads. Registered after {@link DtPayloads#ALL}. */
    public static final List<Spec<?>> ALL = List.of(
        spec(Direction.S2C, CarriageIndexPacket.TYPE, CarriageIndexPacket.STREAM_CODEC, CarriageIndexPacket::handle),
        spec(Direction.S2C, EditorStatusPacket.TYPE, EditorStatusPacket.STREAM_CODEC, EditorStatusPacket::handle),
        spec(Direction.S2C, PartAssignmentSyncPacket.TYPE, PartAssignmentSyncPacket.STREAM_CODEC, PartAssignmentSyncPacket::handle),
        spec(Direction.C2S, PartAssignmentEditPacket.TYPE, PartAssignmentEditPacket.STREAM_CODEC, PartAssignmentEditPacket::handle),
        spec(Direction.C2S, PartMenuTogglePacket.TYPE, PartMenuTogglePacket.STREAM_CODEC, PartMenuTogglePacket::handle),
        spec(Direction.C2S, BlockVariantEditPacket.TYPE, BlockVariantEditPacket.STREAM_CODEC, BlockVariantEditPacket::handle),
        spec(Direction.C2S, BlockVariantMenuTogglePacket.TYPE, BlockVariantMenuTogglePacket.STREAM_CODEC, BlockVariantMenuTogglePacket::handle),
        spec(Direction.C2S, TemplateBlocksMenuTogglePacket.TYPE, TemplateBlocksMenuTogglePacket.STREAM_CODEC, TemplateBlocksMenuTogglePacket::handle),
        spec(Direction.C2S, TemplateBlocksEditPacket.TYPE, TemplateBlocksEditPacket.STREAM_CODEC, TemplateBlocksEditPacket::handle),
        spec(Direction.S2C, BlockVariantLockIdsPacket.TYPE, BlockVariantLockIdsPacket.STREAM_CODEC, BlockVariantLockIdsPacket::handle),
        spec(Direction.S2C, EditorPlotLabelsPacket.TYPE, EditorPlotLabelsPacket.STREAM_CODEC, EditorPlotLabelsPacket::handle),
        spec(Direction.C2S, EditorPlotActionPacket.TYPE, EditorPlotActionPacket.STREAM_CODEC, EditorPlotActionPacket::handle),
        spec(Direction.S2C, EditorTypeMenusPacket.TYPE, EditorTypeMenusPacket.STREAM_CODEC, EditorTypeMenusPacket::handle),
        spec(Direction.C2S, ManualSpawnRequestPacket.TYPE, ManualSpawnRequestPacket.STREAM_CODEC, ManualSpawnRequestPacket::handle),
        spec(Direction.S2C, BoardingProgressPacket.TYPE, BoardingProgressPacket.STREAM_CODEC, BoardingProgressPacket::handle),
        spec(Direction.C2S, ContainerContentsMenuTogglePacket.TYPE, ContainerContentsMenuTogglePacket.STREAM_CODEC, ContainerContentsMenuTogglePacket::handle),
        spec(Direction.C2S, ContainerContentsEditPacket.TYPE, ContainerContentsEditPacket.STREAM_CODEC, ContainerContentsEditPacket::handle),
        spec(Direction.S2C, PrefabRegistrySyncPacket.TYPE, PrefabRegistrySyncPacket.STREAM_CODEC, PrefabRegistrySyncPacket::handle),
        spec(Direction.C2S, SaveBlockVariantPrefabPacket.TYPE, SaveBlockVariantPrefabPacket.STREAM_CODEC, SaveBlockVariantPrefabPacket::handle),
        spec(Direction.C2S, SaveLootPrefabPacket.TYPE, SaveLootPrefabPacket.STREAM_CODEC, SaveLootPrefabPacket::handle),
        spec(Direction.C2S, EditorUnsavedRequestPacket.TYPE, EditorUnsavedRequestPacket.STREAM_CODEC, EditorUnsavedRequestPacket::handle),
        spec(Direction.S2C, EditorUnsavedListPacket.TYPE, EditorUnsavedListPacket.STREAM_CODEC, EditorUnsavedListPacket::handle),
        spec(Direction.C2S, EditorChangesRequestPacket.TYPE, EditorChangesRequestPacket.STREAM_CODEC, EditorChangesRequestPacket::handle),
        spec(Direction.S2C, EditorChangesListPacket.TYPE, EditorChangesListPacket.STREAM_CODEC, EditorChangesListPacket::handle),
        spec(Direction.C2S, PackageListRequestPacket.TYPE, PackageListRequestPacket.STREAM_CODEC, PackageListRequestPacket::handle),
        spec(Direction.S2C, PackageListSyncPacket.TYPE, PackageListSyncPacket.STREAM_CODEC, PackageListSyncPacket::handle),
        spec(Direction.C2S, StartingBookClosedPacket.TYPE, StartingBookClosedPacket.STREAM_CODEC, StartingBookClosedPacket::handle),
        spec(Direction.C2S, BookReadClosedPacket.TYPE, BookReadClosedPacket.STREAM_CODEC, BookReadClosedPacket::handle),
        spec(Direction.C2S, LetterDraftToLecternPacket.TYPE, LetterDraftToLecternPacket.STREAM_CODEC, LetterDraftToLecternPacket::handle),
        spec(Direction.C2S, BugReportLogsPacket.TYPE, BugReportLogsPacket.STREAM_CODEC, BugReportLogsPacket::handle),
        spec(Direction.S2C, CinematicIntroPacket.TYPE, CinematicIntroPacket.STREAM_CODEC, CinematicIntroPacket::handle),
        spec(Direction.S2C, CinematicPreloadBeginPacket.TYPE, CinematicPreloadBeginPacket.STREAM_CODEC, CinematicPreloadBeginPacket::handle),
        spec(Direction.C2S, CinematicDonePacket.TYPE, CinematicDonePacket.STREAM_CODEC, CinematicDonePacket::handle),
        spec(Direction.S2C, ShowFreePlayConfirmPacket.TYPE, ShowFreePlayConfirmPacket.STREAM_CODEC, ShowFreePlayConfirmPacket::handle),
        spec(Direction.C2S, FreePlayConfirmResponsePacket.TYPE, FreePlayConfirmResponsePacket.STREAM_CODEC, FreePlayConfirmResponsePacket::handle),
        spec(Direction.S2C, CaptureEchoPacket.TYPE, CaptureEchoPacket.STREAM_CODEC, CaptureEchoPacket::handle),
        spec(Direction.C2S, EchoPhotoPacket.TYPE, EchoPhotoPacket.STREAM_CODEC, EchoPhotoPacket::handle),
        spec(Direction.C2S, ConsentSyncPacket.TYPE, ConsentSyncPacket.STREAM_CODEC, ConsentSyncPacket::handle),
        spec(Direction.S2C, ConsentUpdatePacket.TYPE, ConsentUpdatePacket.STREAM_CODEC, ConsentUpdatePacket::handle),
        spec(Direction.C2S, StagePanelEditPacket.TYPE, StagePanelEditPacket.STREAM_CODEC, StagePanelEditPacket::handle)
    );
}
