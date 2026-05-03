package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.DebugFlagsState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client snapshot of the debug flags exposed in the in-world
 * Debug menu. Sent on player join (so the menu's Toggle states render
 * correctly the first time it's opened) and again whenever a flag is
 * toggled (so all connected clients immediately see the new state).
 *
 * <p>{@code wireframesEnabled} gates every world-space gap-overlay
 * renderer + the HUD distance-to-next-group line; {@code manualSpawnMode}
 * mirrors {@link games.brennan.dungeontrain.train.TrainCarriageAppender#MANUAL_MODE}
 * so the menu's Auto / Manual toggle reflects current server truth.</p>
 */
public record DebugFlagsPacket(boolean wireframesEnabled, boolean manualSpawnMode) implements CustomPacketPayload {

    public static final Type<DebugFlagsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "debug_flags"));

    public static final StreamCodec<FriendlyByteBuf, DebugFlagsPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeBoolean(packet.wireframesEnabled);
                buf.writeBoolean(packet.manualSpawnMode);
            },
            buf -> new DebugFlagsPacket(buf.readBoolean(), buf.readBoolean())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DebugFlagsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> DebugFlagsState.applyServerState(packet));
    }
}
