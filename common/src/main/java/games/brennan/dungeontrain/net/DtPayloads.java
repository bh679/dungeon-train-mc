package games.brennan.dungeontrain.net;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.net.platform.DtPayloadContext;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Declarative, loader-neutral table of the Dungeon Train network payloads whose
 * packet classes are {@code :common}-resident: direction, wire type, codec, and
 * handler. {@link DungeonTrainNet} (the NeoForge bridge) registers this table
 * <em>and</em> the root-resident remainder ({@code NeoForgeExtraPayloads.ALL},
 * still-root editor / sibling-coupled packets) into NeoForge's
 * {@code PayloadRegistrar}.
 *
 * <p><b>Split rationale (Fabric port, Stage 4a):</b> this table + the {@link Spec}
 * record now live in {@code :common} so a future Fabric bridge can register the
 * same payloads without duplicating the table. Packet classes that still reach
 * into root/editor/client state (the editor cluster, cinematic/consent/echo
 * packets, bug-report + package menus) can't compile in {@code :common} yet, so
 * their specs stay in the root {@code NeoForgeExtraPayloads} table until those
 * classes migrate. Each packet is registered <b>exactly once</b>, from exactly
 * one of the two tables.
 *
 * <p><b>Order / protocol:</b> every payload is keyed on the wire by its own
 * {@code CustomPacketPayload.Type} id (a namespaced {@code ResourceLocation}),
 * never by position in this list — so splitting the old single {@code ALL} into
 * two tables and registering common-then-root has <b>no protocol effect</b>. The
 * handshake version string ({@link DungeonTrainNet#PROTOCOL_VERSION}) is likewise
 * unchanged. Relative order within each table is preserved from the original
 * combined list for readability; don't rename payload type ids, only append new
 * specs (to whichever table the packet class lives in).</p>
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

    /**
     * Package-visible factory shared by this table and the root
     * {@code NeoForgeExtraPayloads} table (same package, {@code games.brennan.dungeontrain.net},
     * compiled into the root jar via the common srcDir wiring).
     */
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
        spec(Direction.S2C, PartVisibilityPacket.TYPE, PartVisibilityPacket.STREAM_CODEC, PartVisibilityPacket::handle)
    );
}
