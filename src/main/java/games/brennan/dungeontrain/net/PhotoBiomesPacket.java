package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EarnedPhotos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * Server → client: every biome the local player has photographed in this world. The biome album shows
 * only these, so photos kept from another world or a Free Play run don't count here — see
 * {@link EarnedPhotos#visibleEntries}. Sent on join and respawn; each new biome after that arrives with
 * its own photo ({@link EarnedPhotoPacket}).
 */
public record PhotoBiomesPacket(List<String> biomes) implements CustomPacketPayload {

    public static final Type<PhotoBiomesPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "photo_biomes"));

    public static final StreamCodec<FriendlyByteBuf, PhotoBiomesPacket> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), PhotoBiomesPacket::biomes,
        PhotoBiomesPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound handler — only ever runs on the physical client. */
    public static void handle(PhotoBiomesPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EarnedPhotos.setPhotographedBiomes(packet.biomes()));
    }
}
