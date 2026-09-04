package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
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
 */
public record RelayBuildPreviewPacket(int relayId, boolean found, CompoundTag template)
        implements CustomPacketPayload {

    public static final Type<RelayBuildPreviewPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "relay_build_preview"));

    public static final StreamCodec<FriendlyByteBuf, RelayBuildPreviewPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.relayId);
                buf.writeBoolean(packet.found);
                buf.writeNbt(packet.template);
            },
            buf -> new RelayBuildPreviewPacket(buf.readVarInt(), buf.readBoolean(),
                buf.readNbt() instanceof CompoundTag tag ? tag : new CompoundTag())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RelayBuildPreviewPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> RelayBuildPreviews.accept(packet.relayId, packet.found, packet.template));
    }
}
