package games.brennan.dungeontrain.net;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.net.platform.DtPayloadContext;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Declarative, loader-neutral table of <b>all</b> Dungeon Train network payloads:
 * direction, wire type, codec, and handler. {@link DungeonTrainNet} (the NeoForge
 * bridge) registers this single table into NeoForge's {@code PayloadRegistrar}; a
 * future Fabric bridge registers the identical table without duplication.
 *
 * <p><b>Split rationale (Fabric port):</b> Stage 4a lifted this table + the
 * {@link Spec} record into {@code :common} and temporarily left the editor /
 * client / sibling-coupled packet specs in a root {@code NeoForgeExtraPayloads}
 * companion table (their packet classes couldn't yet compile in {@code :common}).
 * Stage 4e migrated the last of those packet classes to {@code :common} and folded
 * that remainder back in here, so there is once again one table. Each packet is
 * registered <b>exactly once</b>.
 *
 * <p><b>Order / protocol:</b> every payload is keyed on the wire by its own
 * {@code CustomPacketPayload.Type} id (a namespaced {@code ResourceLocation}),
 * never by position in this list — so consolidating the two tables (common specs
 * first, then the former remainder, exactly as they registered before) has
 * <b>no protocol effect</b>. The handshake version string
 * ({@link DungeonTrainNet#PROTOCOL_VERSION}) is unchanged. Relative order is
 * preserved from the original combined list for readability; don't rename payload
 * type ids, only append new specs.</p>
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

    /** Package-visible factory for the {@link #ALL} table entries. */
    static <T extends CustomPacketPayload> Spec<T> spec(
            Direction direction,
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, DtPayloadContext> handler) {
        return new Spec<>(direction, type, codec, handler);
    }

    /** The {@code :common}-resident payloads. Registered first by {@link DungeonTrainNet}. */
    public static final List<Spec<?>> ALL = List.of(
        spec(Direction.S2C, VariantHoverPacket.TYPE, VariantHoverPacket.STREAM_CODEC, VariantHoverPacket::handle),
        spec(Direction.C2S, VariantHotkeyPacket.TYPE, VariantHotkeyPacket.STREAM_CODEC, VariantHotkeyPacket::handle),
        spec(Direction.S2C, BlockVariantSyncPacket.TYPE, BlockVariantSyncPacket.STREAM_CODEC, BlockVariantSyncPacket::handle),
        spec(Direction.S2C, TemplateBlocksSyncPacket.TYPE, TemplateBlocksSyncPacket.STREAM_CODEC, TemplateBlocksSyncPacket::handle),
        spec(Direction.S2C, BlockVariantOutlinePacket.TYPE, BlockVariantOutlinePacket.STREAM_CODEC, BlockVariantOutlinePacket::handle),
        spec(Direction.S2C, CarriageGroupGapPacket.TYPE, CarriageGroupGapPacket.STREAM_CODEC, CarriageGroupGapPacket::handle),
        spec(Direction.S2C, CarriageNextSpawnPacket.TYPE, CarriageNextSpawnPacket.STREAM_CODEC, CarriageNextSpawnPacket::handle),
        spec(Direction.S2C, CarriageSpawnCollisionPacket.TYPE, CarriageSpawnCollisionPacket.STREAM_CODEC, CarriageSpawnCollisionPacket::handle),
        spec(Direction.S2C, DebugFlagsPacket.TYPE, DebugFlagsPacket.STREAM_CODEC, DebugFlagsPacket::handle),
        spec(Direction.C2S, ContainerHotkeyPacket.TYPE, ContainerHotkeyPacket.STREAM_CODEC, ContainerHotkeyPacket::handle),
        spec(Direction.S2C, ContainerContentsSyncPacket.TYPE, ContainerContentsSyncPacket.STREAM_CODEC, ContainerContentsSyncPacket::handle),

        // Lectern letters: server → client to open the book sign screen.
        spec(Direction.S2C, OpenLetterEditorPacket.TYPE, OpenLetterEditorPacket.STREAM_CODEC, OpenLetterEditorPacket::handle),

        // Death-screen run-stats snapshot (S2C) + scenic ride photo (C2S) for the top-level death report.
        spec(Direction.S2C, DeathStatsPacket.TYPE, DeathStatsPacket.STREAM_CODEC, DeathStatsPacket::handle),
        spec(Direction.C2S, DeathPhotoPacket.TYPE, DeathPhotoPacket.STREAM_CODEC, DeathPhotoPacket::handle),

        // On-train spawn deck-hold: server → joining/respawning player.
        spec(Direction.S2C, SpawnDeckHoldPacket.TYPE, SpawnDeckHoldPacket.STREAM_CODEC, SpawnDeckHoldPacket::handle),

        // Advancements keybind hint: server → the earning player on a gameplay advancement.
        spec(Direction.S2C, AdvancementsHintPacket.TYPE, AdvancementsHintPacket.STREAM_CODEC, AdvancementsHintPacket::handle),

        // Pause-menu "Abandon This Run": client → server kill request.
        spec(Direction.C2S, AbandonRunPacket.TYPE, AbandonRunPacket.STREAM_CODEC, AbandonRunPacket::handle),

        // Network-access consent (community shared books): client → server login sync.
        spec(Direction.C2S, NetworkConsentSyncPacket.TYPE, NetworkConsentSyncPacket.STREAM_CODEC, NetworkConsentSyncPacket::handle),

        // World disintegration band: server → joining player with the per-world carriage length + train flag.
        spec(Direction.S2C, VoidBandSyncPacket.TYPE, VoidBandSyncPacket.STREAM_CODEC, VoidBandSyncPacket::handle),

        // Stage Blocks panel: per-stage row icon strips (S2C, own channel) + panel detail sync (S2C).
        spec(Direction.S2C, StageBlockStripsPacket.TYPE, StageBlockStripsPacket.STREAM_CODEC, StageBlockStripsPacket::handle),
        spec(Direction.S2C, StageBlocksSyncPacket.TYPE, StageBlocksSyncPacket.STREAM_CODEC, StageBlocksSyncPacket::handle),

        // Per-part editor-grid visibility (hidden set) — S2C mirror for the part-list ☑/☐ glyphs.
        spec(Direction.S2C, PartVisibilityPacket.TYPE, PartVisibilityPacket.STREAM_CODEC, PartVisibilityPacket::handle),

        // --- Stage 4e: the former NeoForgeExtraPayloads "root remainder" (40 specs). Their
        // packet classes (editor cluster, carriage/train core, cinematic/consent/echo,
        // bug-report + package menus) all migrated to :common, so their specs consolidate
        // here. Order preserved from that table; appended after the original common specs so
        // the overall registration order is byte-for-byte identical to common-then-remainder.
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
