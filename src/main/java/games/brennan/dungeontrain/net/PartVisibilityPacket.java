package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.net.platform.DtModId;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import games.brennan.dungeontrain.net.platform.DtPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: the set of part plots currently <b>hidden</b> from the editor grid (per
 * {@link games.brennan.dungeontrain.editor.EditorPartVisibility}). The client mirror
 * ({@link games.brennan.dungeontrain.client.menu.ClientPartVisibility}) draws a {@code ☑}/{@code ☐}
 * state glyph on each part-list row from it. Pushed on its own channel, only when the visibility
 * generation moves (same separate-channel pattern as the Stages-panel strips). Anything not in the
 * hidden list is displayed.
 */
public record PartVisibilityPacket(List<Entry> hidden) implements CustomPacketPayload {

    /** One hidden part: kind ordinal + name. */
    public record Entry(byte kindOrd, String name) {}

    public static final Type<PartVisibilityPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, "part_visibility"));

    public static final StreamCodec<FriendlyByteBuf, PartVisibilityPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            PartVisibilityPacket::decode
        );

    public static PartVisibilityPacket empty() {
        return new PartVisibilityPacket(List.of());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(hidden.size());
        for (Entry e : hidden) {
            buf.writeByte(e.kindOrd());
            buf.writeUtf(e.name());
        }
    }

    public static PartVisibilityPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Entry(buf.readByte(), buf.readUtf(64)));
        }
        return new PartVisibilityPacket(List.copyOf(out));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PartVisibilityPacket packet, DtPayloadContext ctx) {
        ctx.enqueueWork(() ->
            games.brennan.dungeontrain.client.menu.ClientPartVisibility.apply(packet));
    }
}
