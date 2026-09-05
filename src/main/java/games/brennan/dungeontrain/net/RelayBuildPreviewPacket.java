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
public record RelayBuildPreviewPacket(int relayId, boolean found, byte[] template)
        implements CustomPacketPayload {

    /** Ceiling on the wire, under NeoForge's own payload limit. The server sends well below it. */
    public static final int MAX_BYTES = 900 * 1024;

    public static final Type<RelayBuildPreviewPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "relay_build_preview"));

    public static final StreamCodec<FriendlyByteBuf, RelayBuildPreviewPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.relayId);
                buf.writeBoolean(packet.found);
                buf.writeByteArray(packet.template);
            },
            buf -> new RelayBuildPreviewPacket(buf.readVarInt(), buf.readBoolean(),
                buf.readByteArray(MAX_BYTES))
        );

    /** The "there is no picture for this one" answer, which a tile is still owed. */
    public static RelayBuildPreviewPacket none(int relayId) {
        return new RelayBuildPreviewPacket(relayId, false, new byte[0]);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RelayBuildPreviewPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            CompoundTag tag = packet.found() ? BuilderRelayPreview.decode(packet.template()) : null;
            RelayBuildPreviews.accept(packet.relayId(), tag != null, tag);
        });
    }
}
