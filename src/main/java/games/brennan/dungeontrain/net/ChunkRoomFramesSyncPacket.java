package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ChunkRoomFramesClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: one dimensional carriage room's frames, for the Frames screen.
 *
 * @param assigned  what the room's {@code .frames.json} names, in file order
 * @param available every frame that exists — the Add picker's choices
 */
public record ChunkRoomFramesSyncPacket(String room, List<Assigned> assigned, List<String> available)
    implements CustomPacketPayload {

    /** Room and frame names are short ids; this bounds the strings on the wire. */
    static final int NAME_MAX = 64;
    /** Bounds each list so a hand-edited file cannot build an oversized packet. */
    private static final int LIST_MAX = 512;

    public record Assigned(String name, int weight) {}

    public static final Type<ChunkRoomFramesSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "chunk_room_frames_sync"));

    public static final StreamCodec<FriendlyByteBuf, ChunkRoomFramesSyncPacket> STREAM_CODEC =
        StreamCodec.of(ChunkRoomFramesSyncPacket::encode, ChunkRoomFramesSyncPacket::decode);

    private static void encode(FriendlyByteBuf buf, ChunkRoomFramesSyncPacket p) {
        buf.writeUtf(p.room(), NAME_MAX);
        List<Assigned> assigned = p.assigned().subList(0, Math.min(LIST_MAX, p.assigned().size()));
        buf.writeVarInt(assigned.size());
        for (Assigned a : assigned) {
            buf.writeUtf(a.name(), NAME_MAX);
            buf.writeVarInt(a.weight());
        }
        List<String> available = p.available().subList(0, Math.min(LIST_MAX, p.available().size()));
        buf.writeVarInt(available.size());
        for (String name : available) buf.writeUtf(name, NAME_MAX);
    }

    private static ChunkRoomFramesSyncPacket decode(FriendlyByteBuf buf) {
        String room = buf.readUtf(NAME_MAX);
        int n = Math.min(LIST_MAX, buf.readVarInt());
        List<Assigned> assigned = new ArrayList<>(n);
        for (int i = 0; i < n; i++) assigned.add(new Assigned(buf.readUtf(NAME_MAX), buf.readVarInt()));
        int m = Math.min(LIST_MAX, buf.readVarInt());
        List<String> available = new ArrayList<>(m);
        for (int i = 0; i < m; i++) available.add(buf.readUtf(NAME_MAX));
        return new ChunkRoomFramesSyncPacket(room, List.copyOf(assigned), List.copyOf(available));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ChunkRoomFramesSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChunkRoomFramesClient.accept(packet));
    }
}
