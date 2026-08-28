package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.PlayerActivityTracker;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: this client's pause screen just opened or closed. Sent on the transition only —
 * one packet per pause, one per un-pause.
 *
 * <p>The server cannot see a client's screen, and on a dedicated server the world keeps ticking
 * behind the pause menu, so without this a paused player would keep banking time on the train.
 * Received state feeds {@link PlayerActivityTracker#setPaused}, which freezes that player's time
 * counters until they come back. A client that never sends it (older build, other loader) simply
 * falls back to the tracker's five-minute idle rule.</p>
 *
 * <p>Not a trust concern: the only thing a spoofed {@code paused=true} buys the sender is their own
 * stats not accruing.</p>
 */
public record PlayerPausedPacket(boolean paused) implements CustomPacketPayload {

    public static final Type<PlayerPausedPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "player_paused"));

    public static final StreamCodec<FriendlyByteBuf, PlayerPausedPacket> STREAM_CODEC =
        StreamCodec.of(PlayerPausedPacket::encode, PlayerPausedPacket::decode);

    private static void encode(FriendlyByteBuf buf, PlayerPausedPacket p) {
        buf.writeBoolean(p.paused);
    }

    private static PlayerPausedPacket decode(FriendlyByteBuf buf) {
        return new PlayerPausedPacket(buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PlayerPausedPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            PlayerActivityTracker.setPaused(player, packet.paused);
        });
    }
}
