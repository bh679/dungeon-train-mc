package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ChunkFrameRoomsClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: which chunk dimensions a frame dresses, for the Chunk dimensions screen.
 *
 * @param rooms    every chunk-dimension room variant, in registry order
 * @param selected the ones this frame dresses
 * @param all      true when the frame is set to every chunk dimension (so new ones join it too)
 * @param weight   how often it is picked against the other frames dressing the same room
 */
public record ChunkFrameRoomsSyncPacket(String frame, List<String> rooms, List<String> selected, boolean all,
                                        int weight) implements CustomPacketPayload {

    static final int NAME_MAX = 64;
    private static final int LIST_MAX = 256;

    public static final Type<ChunkFrameRoomsSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "chunk_frame_rooms_sync"));

    public static final StreamCodec<FriendlyByteBuf, ChunkFrameRoomsSyncPacket> STREAM_CODEC =
        StreamCodec.of(ChunkFrameRoomsSyncPacket::encode, ChunkFrameRoomsSyncPacket::decode);

    private static void encode(FriendlyByteBuf buf, ChunkFrameRoomsSyncPacket p) {
        buf.writeUtf(p.frame(), NAME_MAX);
        writeList(buf, p.rooms());
        writeList(buf, p.selected());
        buf.writeBoolean(p.all());
        buf.writeVarInt(p.weight());
    }

    private static ChunkFrameRoomsSyncPacket decode(FriendlyByteBuf buf) {
        return new ChunkFrameRoomsSyncPacket(buf.readUtf(NAME_MAX), readList(buf), readList(buf),
            buf.readBoolean(), buf.readVarInt());
    }

    private static void writeList(FriendlyByteBuf buf, List<String> list) {
        List<String> capped = list.subList(0, Math.min(LIST_MAX, list.size()));
        buf.writeVarInt(capped.size());
        for (String s : capped) buf.writeUtf(s, NAME_MAX);
    }

    private static List<String> readList(FriendlyByteBuf buf) {
        int n = Math.min(LIST_MAX, buf.readVarInt());
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(buf.readUtf(NAME_MAX));
        return List.copyOf(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ChunkFrameRoomsSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChunkFrameRoomsClient.accept(packet));
    }
}
