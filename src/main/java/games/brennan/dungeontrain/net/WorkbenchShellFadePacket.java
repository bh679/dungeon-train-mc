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
 * Server → client: the shell box round every contents build staged in the Workbench. The client
 * draws stage placeholder blocks inside these boxes faint ({@code StagePlaceholderBakedModel}), so
 * the shell reads as the frame it is rather than a wall of red tiles. An empty list clears it —
 * sent when the player leaves the Workbench.
 *
 * <p>Sent by {@link games.brennan.dungeontrain.editor.VariantOverlayRenderer} with a per-player
 * dedup on the boxes, like the tunnel envelope.</p>
 */
public record WorkbenchShellFadePacket(List<BoundingBox> boxes) implements CustomPacketPayload {

    /** Sanity cap on decode — a shelf is a handful of builds, never thousands. */
    private static final int MAX_BOXES = 4096;

    public static final Type<WorkbenchShellFadePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "workbench_shell_fade"));

    public static final StreamCodec<FriendlyByteBuf, WorkbenchShellFadePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            WorkbenchShellFadePacket::decode
        );

    public WorkbenchShellFadePacket {
        boxes = List.copyOf(boxes);
    }

    public static WorkbenchShellFadePacket empty() {
        return new WorkbenchShellFadePacket(Collections.emptyList());
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

    public static WorkbenchShellFadePacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_BOXES) {
            throw new IllegalArgumentException("workbench shell box count out of range: " + n);
        }
        List<BoundingBox> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new BoundingBox(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        return new WorkbenchShellFadePacket(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WorkbenchShellFadePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> games.brennan.dungeontrain.client.render.WorkbenchShellFade.apply(packet.boxes()));
    }
}
