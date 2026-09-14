package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EditorObserversState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the world's editor Observers setting (on / off), so the Settings row can draw
 * the active cell.
 *
 * <p>Its own channel for the reason {@link EditorMenusModePacket} has one: {@link EditorStatusPacket}
 * is cleared with {@code empty()} the moment the player steps out of a plot, and this setting has
 * to outlive that. Sent to everyone online when the setting changes, and once on login.</p>
 */
public record EditorObserversPacket(boolean on) implements CustomPacketPayload {

    public static final Type<EditorObserversPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_observers"));

    public static final StreamCodec<FriendlyByteBuf, EditorObserversPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeBoolean(packet.on),
            buf -> new EditorObserversPacket(buf.readBoolean())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorObserversPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorObserversState.set(packet.on));
    }
}
