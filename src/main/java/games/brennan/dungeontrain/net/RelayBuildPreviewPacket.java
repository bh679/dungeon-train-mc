package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayPreview;
import games.brennan.dungeontrain.client.builder.RelayBuildPreviews;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: one relay build's blocks, as the structure NBT a template file would hold.
 *
 * <p>The answer to {@link RelayBuildPreviewRequestPacket}. {@code found} false is an ordinary
 * answer — a build this version cannot read, one too big to be worth a tile-sized picture, a relay
 * that did not reply — and the client remembers it so the tile is not asked for again every
 * frame.</p>
 *
 * <p><b>Carried as bytes, not as a tag.</b> A payload's own {@code readNbt} decodes through an
 * accounter capped at 2 MiB, and tripping it throws inside Netty's decoder — which ends the
 * connection rather than the picture, and did. Bytes cross as bytes; the parse happens in the
 * handler, where a build too heavy to read is a tile that keeps its name plate and nothing
 * else.</p>
 */
public record RelayBuildPreviewPacket(int relayId, int seq, int[] seqs, int[] parentSeqs, String[] authors,
                                      boolean found, boolean retryable, byte[] template)
        implements CustomPacketPayload {

    /** The build as it is now — no version, no index. */
    public RelayBuildPreviewPacket(int relayId, boolean found, boolean retryable, byte[] template) {
        this(relayId, 0, new int[0], new int[0], new String[0], found, retryable, template);
    }

    /** As many versions as cross in one answer, and how long a name may be on the wire. */
    static final int MAX_VERSIONS = 4096;
    static final int MAX_AUTHOR = 64;

    /** Ceiling on the wire, under NeoForge's own payload limit. The server sends well below it. */
    public static final int MAX_BYTES = 900 * 1024;

    public static final Type<RelayBuildPreviewPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "relay_build_preview"));

    public static final StreamCodec<FriendlyByteBuf, RelayBuildPreviewPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.relayId);
                buf.writeVarInt(packet.seq);
                buf.writeVarIntArray(packet.seqs);
                buf.writeVarIntArray(packet.parentSeqs);
                buf.writeCollection(java.util.Arrays.asList(packet.authors), (b, a) -> b.writeUtf(a == null ? "" : a, MAX_AUTHOR));
                buf.writeBoolean(packet.found);
                buf.writeBoolean(packet.retryable);
                buf.writeByteArray(packet.template);
            },
            buf -> new RelayBuildPreviewPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarIntArray(MAX_VERSIONS),
                buf.readVarIntArray(MAX_VERSIONS),
                buf.readCollection(size -> new java.util.ArrayList<String>(Math.min(size, MAX_VERSIONS)),
                    b -> b.readUtf(MAX_AUTHOR)).toArray(new String[0]),
                buf.readBoolean(), buf.readBoolean(), buf.readByteArray(MAX_BYTES))
        );

    /**
     * The "no picture" answer, which a tile is still owed.
     *
     * <p>{@code retryable} says whether it was the moment or the build: a relay that did not answer
     * is worth asking about again, a build too heavy to picture is not.</p>
     */
    public static RelayBuildPreviewPacket none(int relayId, boolean retryable) {
        return new RelayBuildPreviewPacket(relayId, false, retryable, new byte[0]);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RelayBuildPreviewPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            CompoundTag tag = packet.found() ? BuilderRelayPreview.decode(packet.template()) : null;
            RelayBuildPreviews.accept(packet.relayId(), packet.seq(),
                new RelayBuildPreviews.VersionInfo(packet.seqs(), packet.parentSeqs(), packet.authors()),
                tag, packet.retryable());
        });
    }
}
