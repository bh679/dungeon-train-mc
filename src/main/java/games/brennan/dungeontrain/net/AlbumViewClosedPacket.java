package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.photo.album.FoundAlbums;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player finished looking through a found album (another player's, read-only).
 *
 * <p>Exposure shows a signed album in a pure-client screen, like vanilla's book view, so the server
 * never hears it close. Empty payload, like {@link PhotographViewClosedPacket} — the server reads the
 * player's hands itself and burns the found album there ({@link FoundAlbums#handleViewClosed}).</p>
 */
public record AlbumViewClosedPacket() implements CustomPacketPayload {

    public static final Type<AlbumViewClosedPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "album_view_closed"));

    public static final StreamCodec<FriendlyByteBuf, AlbumViewClosedPacket> STREAM_CODEC =
        StreamCodec.unit(new AlbumViewClosedPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AlbumViewClosedPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            FoundAlbums.handleViewClosed(player);
        });
    }
}
