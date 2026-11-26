package games.brennan.dungeontrain.perks.effects.trader;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: the Reroll button on trade menu {@code containerId} was pressed. The server
 * re-checks everything ({@link RerollTradesEvents#tryReroll}); the client's view is only a hint.
 */
public record RerollTradesRequestPacket(int containerId) implements CustomPacketPayload {

    public static final Type<RerollTradesRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "reroll_trades_request"));

    public static final StreamCodec<FriendlyByteBuf, RerollTradesRequestPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> buf.writeVarInt(p.containerId), buf -> new RerollTradesRequestPacket(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RerollTradesRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                RerollTradesEvents.tryReroll(player, packet.containerId());
            }
        });
    }
}
