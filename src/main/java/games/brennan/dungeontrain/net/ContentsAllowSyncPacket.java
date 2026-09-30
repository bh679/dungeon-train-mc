package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientContentsAllowState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: the effective Contents allow-list of one carriage or portal room, for the
 * Contents toggle screen. See {@link ContentsAllowRequestPacket}.
 *
 * @param kind   {@link ContentsAllowRequestPacket#KIND_CARRIAGE} or {@link ContentsAllowRequestPacket#KIND_PORTAL_ROOM}
 * @param target the carriage variant id or portal room name
 * @param rows   every top-level contents id, in registry order — one toggle row each
 * @param off    the ones that will not spawn here (excluded, or opt-in and never switched on)
 */
public record ContentsAllowSyncPacket(String kind, String target, List<String> rows, List<String> off)
        implements CustomPacketPayload {

    static final int NAME_MAX = 128;
    private static final int LIST_MAX = 2048;

    public static final Type<ContentsAllowSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "contents_allow_sync"));

    public static final StreamCodec<FriendlyByteBuf, ContentsAllowSyncPacket> STREAM_CODEC =
        StreamCodec.of(ContentsAllowSyncPacket::encode, ContentsAllowSyncPacket::decode);

    public ContentsAllowSyncPacket {
        rows = List.copyOf(rows);
        off = List.copyOf(off);
    }

    private static void encode(FriendlyByteBuf buf, ContentsAllowSyncPacket p) {
        buf.writeUtf(p.kind(), NAME_MAX);
        buf.writeUtf(p.target(), NAME_MAX);
        writeList(buf, p.rows());
        writeList(buf, p.off());
    }

    private static ContentsAllowSyncPacket decode(FriendlyByteBuf buf) {
        return new ContentsAllowSyncPacket(buf.readUtf(NAME_MAX), buf.readUtf(NAME_MAX),
            readList(buf), readList(buf));
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
        return out;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ContentsAllowSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientContentsAllowState.accept(packet));
    }
}
