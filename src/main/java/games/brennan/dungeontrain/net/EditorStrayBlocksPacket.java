package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server → client snapshot of every block sitting <b>outside</b> the editor template plots —
 * the ones a save would silently drop. Drives the red ghost cubes so an author can see the
 * mistake from anywhere in the editor, including from a plot other than the one it is in.
 *
 * <p>Positions are absolute rather than plot-local, because the whole point of the feature is
 * that a stray belongs to no plot. An empty list clears the client cache — sent when the player
 * leaves the editor band, turns the ghosts off, or the last stray is cleaned up.</p>
 *
 * <p>Sent by {@link games.brennan.dungeontrain.editor.VariantOverlayRenderer} with a per-player
 * dedup on {@link games.brennan.dungeontrain.editor.EditorStrayBlocks#generation()}, so a steady
 * editor generates no traffic at all.</p>
 */
public record EditorStrayBlocksPacket(List<BlockPos> positions) implements CustomPacketPayload {

    public static final Type<EditorStrayBlocksPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_stray_blocks"));

    public static final StreamCodec<FriendlyByteBuf, EditorStrayBlocksPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            EditorStrayBlocksPacket::decode
        );

    public static EditorStrayBlocksPacket empty() {
        return new EditorStrayBlocksPacket(Collections.emptyList());
    }

    public boolean isEmpty() {
        return positions.isEmpty();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(positions.size());
        for (BlockPos pos : positions) {
            buf.writeBlockPos(pos);
        }
    }

    public static EditorStrayBlocksPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<BlockPos> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(buf.readBlockPos());
        }
        return new EditorStrayBlocksPacket(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorStrayBlocksPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
            games.brennan.dungeontrain.client.menu.EditorStrayGhostRenderer.applySnapshot(packet));
    }
}
