package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.builder.BuilderSubmitHintsRequests;
import games.brennan.dungeontrain.editor.SubmitHints;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server → client: the answer to a {@link BuilderSubmitHintsRequestPacket} — which extra questions to ask. */
public record BuilderSubmitHintsPacket(int relayId, SubmitHints.Hints hints) implements CustomPacketPayload {

    public BuilderSubmitHintsPacket {
        hints = hints == null ? SubmitHints.Hints.NONE : hints;
    }

    public static final Type<BuilderSubmitHintsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_submit_hints"));

    public static final StreamCodec<FriendlyByteBuf, BuilderSubmitHintsPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.relayId);
                buf.writeBoolean(packet.hints.redstone());
                buf.writeBoolean(packet.hints.loot());
            },
            buf -> new BuilderSubmitHintsPacket(buf.readVarInt(),
                new SubmitHints.Hints(buf.readBoolean(), buf.readBoolean()))
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderSubmitHintsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> BuilderSubmitHintsRequests.accept(packet.relayId, packet.hints));
    }
}
