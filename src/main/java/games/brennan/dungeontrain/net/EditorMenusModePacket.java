package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EditorMenusModeState;
import games.brennan.dungeontrain.editor.EditorMenusMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the player's editor-menus visibility mode (on / auto / off).
 *
 * <p>Its own channel rather than another field on {@link EditorStatusPacket} on purpose. The
 * status packet describes the plot the player is standing in and is cleared with
 * {@link EditorStatusPacket#empty()} the moment they step out of one — which hard-codes the old
 * {@code partMenuEnabled} flag back to {@code true} and silently reset the setting. The mode has
 * to outlive that, because {@link EditorMenusMode#AUTO} is precisely a rule about being in a plot
 * versus between plots.</p>
 *
 * <p>Sent when the mode changes, when the player leaves the editor (which resets it), and once on
 * login so a reconnect inside a running server session lands on the mode the server still holds.</p>
 */
public record EditorMenusModePacket(String mode) implements CustomPacketPayload {

    /** Wire cap for the mode token — the longest is {@code "auto"}; 16 is slack for a future one. */
    private static final int MODE_MAX = 16;

    public static final Type<EditorMenusModePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_menus_mode"));

    public static final StreamCodec<FriendlyByteBuf, EditorMenusModePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            EditorMenusModePacket::decode
        );

    public static EditorMenusModePacket of(EditorMenusMode mode) {
        return new EditorMenusModePacket((mode == null ? EditorMenusMode.DEFAULT : mode).id());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(mode == null ? EditorMenusMode.DEFAULT.id() : mode, MODE_MAX);
    }

    public static EditorMenusModePacket decode(FriendlyByteBuf buf) {
        return new EditorMenusModePacket(buf.readUtf(MODE_MAX));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorMenusModePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorMenusModeState.set(EditorMenusMode.parse(packet.mode)));
    }
}
