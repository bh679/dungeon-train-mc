package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.editor.EditorDirtyCheck;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: response to {@link EditorUnsavedRequestPacket}. Carries
 * the per-row dirty / unpromoted state the unsaved-changes confirmation
 * screen renders. Emptiness is meaningful — an empty list tells the
 * client to bypass the screen entirely and dispatch the original
 * {@code /dt editor &lt;cat&gt;} command (clean state preserves today's
 * one-click flow).
 *
 * <p>The packet is also re-broadcast after any save the client triggers
 * from this screen, so the client's local optimistic grey-out reconciles
 * with the authoritative server state.</p>
 */
public record EditorUnsavedListPacket(List<EditorDirtyCheck.DirtyEntry> rows) implements CustomPacketPayload {

    public static final Type<EditorUnsavedListPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_unsaved_list"));

    public static final StreamCodec<FriendlyByteBuf, EditorUnsavedListPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            EditorUnsavedListPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(rows.size());
        for (EditorDirtyCheck.DirtyEntry r : rows) {
            buf.writeUtf(r.categoryId(), 32);
            buf.writeUtf(r.modelId(), 64);
            buf.writeUtf(r.displayName(), 96);
            buf.writeBoolean(r.isUnsaved());
            buf.writeBoolean(r.isUnpromoted());
        }
    }

    public static EditorUnsavedListPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<EditorDirtyCheck.DirtyEntry> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String cat = buf.readUtf(32);
            String id = buf.readUtf(64);
            String display = buf.readUtf(96);
            boolean unsaved = buf.readBoolean();
            boolean unpromoted = buf.readBoolean();
            rows.add(new EditorDirtyCheck.DirtyEntry(cat, id, display, unsaved, unpromoted));
        }
        return new EditorUnsavedListPacket(rows);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorUnsavedListPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorStatusHudOverlay.setUnsavedList(packet.rows));
    }
}
