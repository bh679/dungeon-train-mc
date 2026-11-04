package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.tribute.TributeRoomVisitService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C2S: the visitor is done — Board anew. The server saves the room, sweeps the site and answers with
 * {@link TributeRoomSavedPacket}; on a server it also puts them back on the train.
 */
public record TributeRoomVisitEndPacket() implements CustomPacketPayload {

    public static final Type<TributeRoomVisitEndPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "tribute_room_visit_end"));

    public static final StreamCodec<FriendlyByteBuf, TributeRoomVisitEndPacket> STREAM_CODEC =
        StreamCodec.unit(new TributeRoomVisitEndPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TributeRoomVisitEndPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) TributeRoomVisitService.endFromClient(player);
        });
    }
}
