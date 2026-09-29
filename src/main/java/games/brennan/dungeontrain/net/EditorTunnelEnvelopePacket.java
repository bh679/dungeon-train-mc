package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server → client: the world-space train envelope of every tunnel plot
 * ({@link games.brennan.dungeontrain.editor.EditorTunnelEnvelopes#snapshot}). The client washes any
 * block inside one of these boxes red. An empty list clears the overlay — sent when the player
 * leaves the Tracks category or the build area.
 *
 * <p>Sent by {@link games.brennan.dungeontrain.editor.VariantOverlayRenderer} with a per-player
 * dedup on {@link games.brennan.dungeontrain.editor.EditorTunnelEnvelopes#key}.</p>
 */
public record EditorTunnelEnvelopePacket(List<BoundingBox> boxes) implements CustomPacketPayload {

    /** Sanity cap on decode — a plot grid is a handful of boxes, never thousands. */
    private static final int MAX_BOXES = 4096;

    public static final Type<EditorTunnelEnvelopePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_tunnel_envelope"));

    public static final StreamCodec<FriendlyByteBuf, EditorTunnelEnvelopePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            EditorTunnelEnvelopePacket::decode
        );

    public EditorTunnelEnvelopePacket {
        boxes = List.copyOf(boxes);
    }

    public static EditorTunnelEnvelopePacket empty() {
        return new EditorTunnelEnvelopePacket(Collections.emptyList());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(boxes.size());
        for (BoundingBox box : boxes) {
            buf.writeVarInt(box.minX());
            buf.writeVarInt(box.minY());
            buf.writeVarInt(box.minZ());
            buf.writeVarInt(box.maxX());
            buf.writeVarInt(box.maxY());
            buf.writeVarInt(box.maxZ());
        }
    }

    public static EditorTunnelEnvelopePacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_BOXES) {
            throw new IllegalArgumentException("tunnel envelope count out of range: " + n);
        }
        List<BoundingBox> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new BoundingBox(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        return new EditorTunnelEnvelopePacket(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorTunnelEnvelopePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
            games.brennan.dungeontrain.client.menu.EditorTunnelEnvelopeWashRenderer.applySnapshot(packet));
    }
}
