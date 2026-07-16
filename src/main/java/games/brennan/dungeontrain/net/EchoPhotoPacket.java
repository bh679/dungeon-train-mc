package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.echo.RemoteEchoEncounters;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Client → server: the framed screenshot of a remote echo (keyed by the echo's entity UUID) as PNG
 * bytes, sent in response to a {@link CaptureEchoPacket}. The server buffers it on the open encounter
 * journal ({@link RemoteEchoEncounters#onPhoto}); when the encounter ends it's attached to the Discord
 * story embed.
 *
 * <p>Mirrors {@link DeathPhotoPacket}: the down-scaled (≤1080px) shot is JPEG-encoded for this send,
 * keeping it a single packet well under the 1&nbsp;MB cap the codec also enforces.</p>
 */
public record EchoPhotoPacket(UUID echoId, byte[] png) implements CustomPacketPayload {

    public static final Type<EchoPhotoPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "echo_photo"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EchoPhotoPacket> STREAM_CODEC =
        StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, EchoPhotoPacket::echoId,
            ByteBufCodecs.byteArray(1024 * 1024), EchoPhotoPacket::png,
            EchoPhotoPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EchoPhotoPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            RemoteEchoEncounters.onPhoto(player, packet.echoId(), packet.png());
        });
    }
}
