package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.EditorHistoryState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: what Undo and Redo would step through next.
 *
 * <p>The editor's history lives on the server, so the client cannot name the step a button is
 * about to reverse. It is sent as its own payload rather than folded into
 * {@link EditorStatusPacket} because it changes on a different beat — every edit, rather than
 * every plot the player walks into — and that packet's dedup key would have to grow to notice.</p>
 *
 * <p>Both strings are {@code ""} when the matching stack is empty, which is what the menu reads as
 * "nothing to undo".</p>
 */
public record EditorHistoryPacket(String undoLabel, String redoLabel) implements CustomPacketPayload {

    /** Long enough for a step label and its plot key; a hostile packet cannot allocate wildly. */
    private static final int MAX_LABEL = 128;

    public static final Type<EditorHistoryPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_history"));

    public static final StreamCodec<FriendlyByteBuf, EditorHistoryPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeUtf(packet.undoLabel, MAX_LABEL);
                buf.writeUtf(packet.redoLabel, MAX_LABEL);
            },
            buf -> new EditorHistoryPacket(buf.readUtf(MAX_LABEL), buf.readUtf(MAX_LABEL)));

    public EditorHistoryPacket {
        if (undoLabel == null) undoLabel = "";
        if (redoLabel == null) redoLabel = "";
    }

    /** Nothing on either stack — what a player outside the editor is told. */
    public static EditorHistoryPacket empty() {
        return new EditorHistoryPacket("", "");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorHistoryPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorHistoryState.accept(packet));
    }
}
