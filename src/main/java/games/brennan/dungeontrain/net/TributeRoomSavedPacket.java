package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.tributeroom.TributeTvVisit;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: the tribute room is saved and the visit is over. {@code newWorld} tells a singleplayer client it
 * now owns the run's end — build the next world — where a server has already put the player back on
 * the train.
 */
public record TributeRoomSavedPacket(boolean newWorld) implements CustomPacketPayload {

    public static final Type<TributeRoomSavedPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "tribute_room_saved"));

    public static final StreamCodec<FriendlyByteBuf, TributeRoomSavedPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> buf.writeBoolean(packet.newWorld),
            buf -> new TributeRoomSavedPacket(buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TributeRoomSavedPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> TributeTvVisit.onSaved(packet.newWorld));
    }
}
