package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.EditorSaveAsScreen;
import games.brennan.dungeontrain.editor.EditorTemplateAddress;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: a Train Editor Save landed on a template the mod ships, outside dev mode — ask
 * the player what to do instead of writing over it.
 *
 * <p>The server decides rather than the client because every Editor Save — the X menu, the header
 * icon, the editor screen, the plot panel, the unsaved-changes list, a typed {@code /dt save} —
 * already ends up at one of four server paths, and none of the client's views of the plot know
 * whether the template ships. One check there covers all of them.</p>
 *
 * @param address     the template being saved; comes back unchanged in {@link EditorSaveAsPacket}
 * @param displayName how the template reads to the player, for the screen's note
 */
public record EditorSaveAsPromptPacket(EditorTemplateAddress address, String displayName)
        implements CustomPacketPayload {

    public static final Type<EditorSaveAsPromptPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_save_as_prompt"));

    public static final StreamCodec<FriendlyByteBuf, EditorSaveAsPromptPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                EditorSaveAsPacket.writeAddress(buf, packet.address);
                buf.writeUtf(packet.displayName, 64);
            },
            buf -> new EditorSaveAsPromptPacket(EditorSaveAsPacket.readAddress(buf), buf.readUtf(64))
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorSaveAsPromptPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorSaveAsScreen.open(packet.address, packet.displayName));
    }
}
