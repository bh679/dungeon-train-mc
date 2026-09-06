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
 * Client → server: the player did something inside an open screen. {@code nonLook} separates the
 * two clocks {@link PlayerActivityTracker} keeps — {@code false} is cursor movement (the 30-second
 * mouse clock), {@code true} is a click or key press (the 5-minute input clock).
 *
 * <p>Needed because a screen freezes the camera: however much the mouse moves in an inventory, the
 * player's server-side yaw and pitch do not change, and the mouse rule would call an actively
 * sorting player idle. Rate-limited to one packet per kind per second by
 * {@link games.brennan.dungeontrain.client.ClientActivityReporter}.</p>
 *
 * <p>Not a trust concern: a spoofed packet only keeps the sender's own clock alive, and the
 * carriage-progress rule still gates time on the train independently.</p>
 */
public record ClientInputPacket(boolean nonLook) implements CustomPacketPayload {

    public static final Type<ClientInputPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "client_input"));

    public static final StreamCodec<FriendlyByteBuf, ClientInputPacket> STREAM_CODEC =
        StreamCodec.of(ClientInputPacket::encode, ClientInputPacket::decode);

    private static void encode(FriendlyByteBuf buf, ClientInputPacket p) {
        buf.writeBoolean(p.nonLook);
    }

    private static ClientInputPacket decode(FriendlyByteBuf buf) {
        return new ClientInputPacket(buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientInputPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            long now = player.level().getGameTime();
            // Menu activity is mouse activity either way; a click or key press additionally
            // counts as the non-look input the 5-minute clock is waiting for.
            PlayerActivityTracker.markLook(player.getUUID(), now);
            if (packet.nonLook) {
                PlayerActivityTracker.markInput(player.getUUID(), now, "menu input");
            }
        });
    }
}
