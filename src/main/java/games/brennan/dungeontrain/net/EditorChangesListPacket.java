package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.editor.EditorDirtyCheck;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: response to {@link EditorChangesRequestPacket}.
 * Carries the per-position diff list the changes drilldown screen
 * renders. {@link #categoryId} and {@link #modelId} echo the request
 * so a stale response (player drilled into a different template
 * before this one returned) can be ignored.
 */
public record EditorChangesListPacket(String categoryId, String modelId,
                                      List<EditorDirtyCheck.DiffEntry> changes) implements CustomPacketPayload {

    public static final Type<EditorChangesListPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_changes_list"));

    public static final StreamCodec<FriendlyByteBuf, EditorChangesListPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            EditorChangesListPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(categoryId, 32);
        buf.writeUtf(modelId, 96);
        buf.writeVarInt(changes.size());
        for (EditorDirtyCheck.DiffEntry d : changes) {
            BlockPos p = d.localPos();
            buf.writeVarInt(p.getX());
            buf.writeVarInt(p.getY());
            buf.writeVarInt(p.getZ());
            buf.writeUtf(d.expectedDescription(), 64);
            buf.writeUtf(d.liveDescription(), 64);
        }
    }

    public static EditorChangesListPacket decode(FriendlyByteBuf buf) {
        String cat = buf.readUtf(32);
        String id = buf.readUtf(96);
        int n = buf.readVarInt();
        List<EditorDirtyCheck.DiffEntry> changes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int x = buf.readVarInt();
            int y = buf.readVarInt();
            int z = buf.readVarInt();
            String exp = buf.readUtf(64);
            String live = buf.readUtf(64);
            changes.add(new EditorDirtyCheck.DiffEntry(new BlockPos(x, y, z), exp, live));
        }
        return new EditorChangesListPacket(cat, id, changes);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorChangesListPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorStatusHudOverlay.setChangesList(packet.categoryId, packet.modelId, packet.changes));
    }
}
