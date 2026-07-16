package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.DeathReportBuffer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the death screen's chosen scenic ride photo as PNG bytes, sent when the
 * {@code NarrativeDeathScreen} opens. The server attaches it to the buffered dev/public top-level
 * death report ({@link DeathReportBuffer}); an empty array means "no photo this run", which posts the
 * report with the gear-composite fallback instead.
 *
 * <p>A stored ride shot is downscaled to ≤1080px, so its PNG stays a single packet well under the
 * 1&nbsp;MB payload cap (which the codec also enforces).</p>
 */
public record DeathPhotoPacket(byte[] png) implements CustomPacketPayload {

    public static final Type<DeathPhotoPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "death_photo"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DeathPhotoPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.byteArray(1024 * 1024), DeathPhotoPacket::png,
            DeathPhotoPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DeathPhotoPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            DeathReportBuffer.onPhoto(player, packet.png());
        });
    }
}
