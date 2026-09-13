package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.builder.StagePreviews;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: a stage-stamped carriage's blocks, as the structure NBT a template file holds.
 * The answer to {@link StagePreviewRequestPacket}.
 *
 * <p>Carried as bytes, not as a tag, for the reason {@link RelayBuildPreviewPacket} gives: a
 * payload's own {@code readNbt} trips a capped accounter inside Netty's decoder and ends the
 * connection rather than the picture. The parse happens in the handler.</p>
 *
 * @param found false when the stage or carriage is unknown, the request came from outside the
 *              editor world, or the capture would not fit on the wire
 */
public record StagePreviewPacket(String stageId, String carriageId, long seed, boolean found, byte[] template)
        implements CustomPacketPayload {

    /** Ceiling on the wire, under NeoForge's own payload limit; a carriage is a few KB. */
    public static final int MAX_BYTES = 900 * 1024;

    public static final Type<StagePreviewPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "stage_preview"));

    public static final StreamCodec<FriendlyByteBuf, StagePreviewPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeUtf(packet.stageId, 64);
                buf.writeUtf(packet.carriageId, 64);
                buf.writeLong(packet.seed);
                buf.writeBoolean(packet.found);
                buf.writeByteArray(packet.template);
            },
            buf -> new StagePreviewPacket(buf.readUtf(64), buf.readUtf(64), buf.readLong(),
                buf.readBoolean(), buf.readByteArray(MAX_BYTES))
        );

    /** The "no picture" answer. */
    public static StagePreviewPacket none(StagePreviewRequestPacket ask) {
        return new StagePreviewPacket(ask.stageId(), ask.carriageId(), ask.seed(), false, new byte[0]);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StagePreviewPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> StagePreviews.accept(packet));
    }
}
