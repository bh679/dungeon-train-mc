package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.chunkparts.ChunkPartKind;
import games.brennan.dungeontrain.portal.chunkparts.ChunkPartRegistry;
import games.brennan.dungeontrain.portal.chunkparts.ChunkRoomPartsStore;
import games.brennan.dungeontrain.train.CarriagePartAssignment;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Client → server: "what is {@code room} framed in, and what could it be?" Answered with a
 * {@link ChunkRoomPartsSyncPacket} for the Chunk Parts screen. Operators only, like every editor read.
 */
public record ChunkRoomPartsRequestPacket(String room) implements CustomPacketPayload {

    public static final Type<ChunkRoomPartsRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "chunk_room_parts_request"));

    public static final StreamCodec<FriendlyByteBuf, ChunkRoomPartsRequestPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> buf.writeUtf(p.room(), ChunkRoomPartsSyncPacket.NAME_MAX),
            buf -> new ChunkRoomPartsRequestPacket(buf.readUtf(ChunkRoomPartsSyncPacket.NAME_MAX)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ChunkRoomPartsRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player) || !player.hasPermissions(2)) return;
            DungeonTrainNet.sendTo(player, build(packet.room()));
        });
    }

    /** The room's assignment and every part it could name — also sent after each edit. */
    public static ChunkRoomPartsSyncPacket build(String room) {
        CarriagePartAssignment assignment = ChunkRoomPartsStore.get(room).orElse(null);
        List<ChunkRoomPartsSyncPacket.Assigned> assigned = new ArrayList<>();
        List<ChunkRoomPartsSyncPacket.Available> available = new ArrayList<>();
        for (ChunkPartKind kind : ChunkPartKind.values()) {
            if (assignment != null) {
                for (CarriagePartAssignment.WeightedName e : assignment.entries(kind.carriageKind())) {
                    if (CarriagePartKind.NONE.equals(e.name())) continue;
                    assigned.add(new ChunkRoomPartsSyncPacket.Assigned(kind.id(), e.name(), e.weight()));
                }
            }
            for (String name : ChunkPartRegistry.names(kind)) {
                available.add(new ChunkRoomPartsSyncPacket.Available(kind.id(), name));
            }
        }
        return new ChunkRoomPartsSyncPacket(room, assigned, available);
    }
}
