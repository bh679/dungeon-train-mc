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
 * C2S: the dead player pressed Next on the perks menu — stand them in their tribute room.
 *
 * <p>Answered with a {@link TributeRoomVisitStatePacket}: active when the room stands, none when the
 * visit could not start (hardcore, no template, already visiting), so the client falls back to the
 * full-screen recap rather than waiting on black.</p>
 */
/** @param prepareOnly stand the room now, ahead of the visit, and answer nothing; the visit itself comes with {@code false} */
public record TributeRoomVisitPacket(boolean prepareOnly) implements CustomPacketPayload {

    public static final Type<TributeRoomVisitPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "tribute_room_visit"));

    public static final StreamCodec<FriendlyByteBuf, TributeRoomVisitPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> buf.writeBoolean(packet.prepareOnly), buf -> new TributeRoomVisitPacket(buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TributeRoomVisitPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (packet.prepareOnly) {
                TributeRoomVisitService.prepare(player);
                return;
            }
            if (!TributeRoomVisitService.begin(player)) {
                DungeonTrainNet.sendTo(player, TributeRoomVisitStatePacket.none());
            }
        });
    }
}
