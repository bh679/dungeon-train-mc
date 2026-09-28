package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrame;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMeta;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMetaStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * Client → server: "which chunk dimensions does {@code frame} dress?" Answered with a
 * {@link ChunkFrameRoomsSyncPacket} for the Chunk dimensions screen. Operators only.
 */
public record ChunkFrameRoomsRequestPacket(String frame) implements CustomPacketPayload {

    public static final Type<ChunkFrameRoomsRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "chunk_frame_rooms_request"));

    public static final StreamCodec<FriendlyByteBuf, ChunkFrameRoomsRequestPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> buf.writeUtf(p.frame(), ChunkFrameRoomsSyncPacket.NAME_MAX),
            buf -> new ChunkFrameRoomsRequestPacket(buf.readUtf(ChunkFrameRoomsSyncPacket.NAME_MAX)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ChunkFrameRoomsRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player) || !player.hasPermissions(2)) return;
            DungeonTrainNet.sendTo(player, build(packet.frame()));
        });
    }

    /** The frame's selection against every chunk dimension there is — also sent after each edit. */
    public static ChunkFrameRoomsSyncPacket build(String frame) {
        ChunkFrameMeta meta = ChunkFrameMetaStore.get(frame);
        List<String> rooms = ChunkFrame.chunkRooms();
        List<String> selected = rooms.stream().filter(meta::appliesTo).toList();
        return new ChunkFrameRoomsSyncPacket(frame, rooms, selected, meta.allRooms(), meta.weight());
    }
}
