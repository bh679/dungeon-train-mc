package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.home.HomeRoomVisitService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C2S: the visitor is done — Board anew. The server saves the room, sweeps the site and answers with
 * {@link HomeRoomSavedPacket}; on a server it also puts them back on the train.
 */
public record HomeRoomVisitEndPacket() implements CustomPacketPayload {

    public static final Type<HomeRoomVisitEndPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "home_room_visit_end"));

    public static final StreamCodec<FriendlyByteBuf, HomeRoomVisitEndPacket> STREAM_CODEC =
        StreamCodec.unit(new HomeRoomVisitEndPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(HomeRoomVisitEndPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) HomeRoomVisitService.endFromClient(player);
        });
    }
}
