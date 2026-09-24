package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import games.brennan.dungeontrain.client.builder.BuilderProfileState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: how a {@link BuilderProfileDeletePacket} ended.
 *
 * <p>Exists for one outcome: {@link BuilderRelayUpload.DeleteOutcome#IN_USE} is a question — "somebody
 * is riding it right now, delete anyway?" — and a chat line cannot ask one. The screen that pressed
 * the button hears this, puts the question up, and answers it with a second, forced press. The
 * other outcomes are told in chat as well; here they only settle the note under the grid.</p>
 */
public record BuilderProfileDeleteResultPacket(int relayId, BuilderRelayUpload.DeleteOutcome outcome)
        implements CustomPacketPayload {

    public static final Type<BuilderProfileDeleteResultPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_profile_delete_result"));

    public static final StreamCodec<FriendlyByteBuf, BuilderProfileDeleteResultPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.relayId);
                buf.writeEnum(packet.outcome);
            },
            buf -> new BuilderProfileDeleteResultPacket(buf.readVarInt(),
                    buf.readEnum(BuilderRelayUpload.DeleteOutcome.class))
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderProfileDeleteResultPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> BuilderProfileState.deleteResult(packet));
    }
}
