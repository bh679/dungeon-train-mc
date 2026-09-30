package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: the cells of the X editor's Blocks page for the plot {@code key} the player is
 * standing in, in page order — a cell's index here is the {@code group} a
 * {@link TemplateBlockGroupsEditPacket} re-skins. An empty {@code key} means the player is in no plot.
 *
 * <p>Block ids go as registry strings, which vanilla keeps identical on both sides.</p>
 */
public record TemplateBlockGroupsSyncPacket(String key, List<Entry> entries) implements CustomPacketPayload {

    /** One cell: its block and how many uses of the template it stands for. */
    public record Entry(String blockId, int count) {}

    public static final Type<TemplateBlockGroupsSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "template_block_groups_sync"));

    public static final StreamCodec<FriendlyByteBuf, TemplateBlockGroupsSyncPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> packet.encode(buf), TemplateBlockGroupsSyncPacket::decode);

    /** Hard cap on cells read off the wire — far above any real template's block kinds. */
    private static final int MAX_ENTRIES = 4096;

    public TemplateBlockGroupsSyncPacket {
        entries = List.copyOf(entries);
    }

    public static TemplateBlockGroupsSyncPacket none() {
        return new TemplateBlockGroupsSyncPacket("", List.of());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(key, 128);
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUtf(e.blockId(), 256);
            buf.writeVarInt(e.count());
        }
    }

    public static TemplateBlockGroupsSyncPacket decode(FriendlyByteBuf buf) {
        String key = buf.readUtf(128);
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_ENTRIES) throw new IllegalArgumentException("Too many block groups: " + n);
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            entries.add(new Entry(buf.readUtf(256), buf.readVarInt()));
        }
        return new TemplateBlockGroupsSyncPacket(key, entries);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TemplateBlockGroupsSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
            games.brennan.dungeontrain.client.menu.editorscreen.BlockGroupsState.applySync(packet));
    }
}
