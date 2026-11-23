package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EditorModeSettingsState;
import games.brennan.dungeontrain.editor.EditorModeSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the world's per-mode editor Settings — Observers, Redstone, TNT, Fluid flow,
 * Auto fly and Mobs, each with its editing and its test value — so the Settings rows can draw the
 * active cell for whichever mode the player is in.
 *
 * <p>Its own channel, like the one-setting packets it replaced: world state that must outlive the
 * per-plot {@link EditorStatusPacket}. Sent to everyone online on every change, and once on login.</p>
 */
public record EditorModeSettingsPacket(int editorBits, int testBits) implements CustomPacketPayload {

    public static final Type<EditorModeSettingsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_mode_settings"));

    public static final StreamCodec<FriendlyByteBuf, EditorModeSettingsPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.editorBits);
                buf.writeVarInt(packet.testBits);
            },
            buf -> new EditorModeSettingsPacket(buf.readVarInt(), buf.readVarInt())
        );

    public static EditorModeSettingsPacket of(EditorModeSettings settings) {
        return new EditorModeSettingsPacket(settings.editorBits(), settings.testBits());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorModeSettingsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorModeSettingsState.set(new EditorModeSettings(packet.editorBits, packet.testBits)));
    }
}
