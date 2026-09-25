package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameRegistry;
import games.brennan.dungeontrain.portal.chunkframe.ChunkRoomFrames;
import games.brennan.dungeontrain.portal.chunkframe.ChunkRoomFramesStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Client → server: "which frames does {@code room} draw from, and which could it?" Answered with a
 * {@link ChunkRoomFramesSyncPacket} for the Frames screen. Operators only, like every editor read.
 */
public record ChunkRoomFramesRequestPacket(String room) implements CustomPacketPayload {

    public static final Type<ChunkRoomFramesRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "chunk_room_frames_request"));

    public static final StreamCodec<FriendlyByteBuf, ChunkRoomFramesRequestPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> buf.writeUtf(p.room(), ChunkRoomFramesSyncPacket.NAME_MAX),
            buf -> new ChunkRoomFramesRequestPacket(buf.readUtf(ChunkRoomFramesSyncPacket.NAME_MAX)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ChunkRoomFramesRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player) || !player.hasPermissions(2)) return;
            DungeonTrainNet.sendTo(player, build(packet.room()));
        });
    }

    /** The room's list and every frame it could name — also sent after each edit. */
    public static ChunkRoomFramesSyncPacket build(String room) {
        List<ChunkRoomFramesSyncPacket.Assigned> assigned = new ArrayList<>();
        for (ChunkRoomFrames.Entry e : ChunkRoomFramesStore.get(room).entries()) {
            assigned.add(new ChunkRoomFramesSyncPacket.Assigned(e.name(), e.weight()));
        }
        return new ChunkRoomFramesSyncPacket(room, assigned, ChunkFrameRegistry.names());
    }
}
