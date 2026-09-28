package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.player.SprintFlyBoost;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the local player's creative sprint-fly multiplier ({@code /dt flyspeed}). Sent
 * on login and whenever the command changes it; the client applies it in
 * {@code mixin/PlayerSprintFlyMixin}. See {@link SprintFlyBoost}.
 */
public record SprintFlyBoostPacket(float multiplier) implements CustomPacketPayload {

    public static final Type<SprintFlyBoostPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "sprint_fly_boost"));

    public static final StreamCodec<FriendlyByteBuf, SprintFlyBoostPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeFloat(packet.multiplier),
            buf -> new SprintFlyBoostPacket(buf.readFloat())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SprintFlyBoostPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> SprintFlyBoost.setClientMultiplier(packet.multiplier));
    }
}
