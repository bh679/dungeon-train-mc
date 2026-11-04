package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.homeroom.HomeTvVisit;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: the home room is saved and the visit is over. {@code newWorld} tells a singleplayer client it
 * now owns the run's end — build the next world — where a server has already put the player back on
 * the train.
 */
public record HomeRoomSavedPacket(boolean newWorld) implements CustomPacketPayload {

    public static final Type<HomeRoomSavedPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "home_room_saved"));

    public static final StreamCodec<FriendlyByteBuf, HomeRoomSavedPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> buf.writeBoolean(packet.newWorld),
            buf -> new HomeRoomSavedPacket(buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(HomeRoomSavedPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> HomeTvVisit.onSaved(packet.newWorld));
    }
}
