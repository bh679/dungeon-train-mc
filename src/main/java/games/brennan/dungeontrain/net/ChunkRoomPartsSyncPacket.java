package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ChunkRoomPartsClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * Server → client: one dimensional carriage room's chunk parts, for the Chunk Parts screen.
 *
 * @param assigned  what the room's {@code .parts.json} names, per kind, in file order
 * @param available every chunk part that exists, per kind — the Add picker's choices
 */
public record ChunkRoomPartsSyncPacket(String room, List<Assigned> assigned, List<Available> available)
    implements CustomPacketPayload {

    /** Room, kind and part names are all short ids; this bounds the strings on the wire. */
    static final int NAME_MAX = 64;
    /** Bounds each list so a hand-edited file cannot build an oversized packet. */
    private static final int LIST_MAX = 512;

    public record Assigned(String kind, String name, int weight) {}

    public record Available(String kind, String name) {}

    public static final Type<ChunkRoomPartsSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "chunk_room_parts_sync"));

    public static final StreamCodec<FriendlyByteBuf, ChunkRoomPartsSyncPacket> STREAM_CODEC =
        StreamCodec.of(ChunkRoomPartsSyncPacket::encode, ChunkRoomPartsSyncPacket::decode);

    private static void encode(FriendlyByteBuf buf, ChunkRoomPartsSyncPacket p) {
        buf.writeUtf(p.room(), NAME_MAX);
        List<Assigned> assigned = p.assigned().subList(0, Math.min(LIST_MAX, p.assigned().size()));
        buf.writeVarInt(assigned.size());
        for (Assigned a : assigned) {
            buf.writeUtf(a.kind(), NAME_MAX);
            buf.writeUtf(a.name(), NAME_MAX);
            buf.writeVarInt(a.weight());
        }
        List<Available> available = p.available().subList(0, Math.min(LIST_MAX, p.available().size()));
        buf.writeVarInt(available.size());
        for (Available a : available) {
            buf.writeUtf(a.kind(), NAME_MAX);
            buf.writeUtf(a.name(), NAME_MAX);
        }
    }

    private static ChunkRoomPartsSyncPacket decode(FriendlyByteBuf buf) {
        String room = buf.readUtf(NAME_MAX);
        int n = Math.min(LIST_MAX, buf.readVarInt());
        List<Assigned> assigned = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            assigned.add(new Assigned(buf.readUtf(NAME_MAX), buf.readUtf(NAME_MAX), buf.readVarInt()));
        }
        int m = Math.min(LIST_MAX, buf.readVarInt());
        List<Available> available = new java.util.ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            available.add(new Available(buf.readUtf(NAME_MAX), buf.readUtf(NAME_MAX)));
        }
        return new ChunkRoomPartsSyncPacket(room, List.copyOf(assigned), List.copyOf(available));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ChunkRoomPartsSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChunkRoomPartsClient.accept(packet));
    }
}
