package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EditorRedstoneTntState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the world's editor Redstone and TNT settings (on / off each), so their Settings
 * rows can draw the active cell. Its own channel for the reason {@link EditorObserversPacket} has
 * one. Sent to everyone online when either setting changes, and once on login.
 */
public record EditorRedstoneTntPacket(boolean redstoneOn, boolean tntOn) implements CustomPacketPayload {

    public static final Type<EditorRedstoneTntPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_redstone_tnt"));

    public static final StreamCodec<FriendlyByteBuf, EditorRedstoneTntPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeBoolean(packet.redstoneOn);
                buf.writeBoolean(packet.tntOn);
            },
            buf -> new EditorRedstoneTntPacket(buf.readBoolean(), buf.readBoolean())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorRedstoneTntPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorRedstoneTntState.set(packet.redstoneOn, packet.tntOn));
    }
}
