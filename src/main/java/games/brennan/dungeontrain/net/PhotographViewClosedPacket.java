package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.DisposableCameraEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player just closed Exposure's photograph view while holding a photo taken
 * with a disposable camera (see {@link games.brennan.dungeontrain.compat.DisposableCamera}).
 *
 * <p>Empty payload, like {@link StartingBookClosedPacket} — the server reads the player's hands
 * itself and decides what burns, in {@link DisposableCameraEvents#handlePhotographViewClosed}.</p>
 */
public record PhotographViewClosedPacket() implements CustomPacketPayload {

    public static final Type<PhotographViewClosedPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "photograph_view_closed"));

    public static final StreamCodec<FriendlyByteBuf, PhotographViewClosedPacket> STREAM_CODEC =
        StreamCodec.unit(new PhotographViewClosedPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PhotographViewClosedPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            DisposableCameraEvents.handlePhotographViewClosed(player);
        });
    }
}
