package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EarnedPhotos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server → client: every biome the local player has photographed in this world, and how many photos
 * they have counted per photo-count category (animal, hostile, passenger). The biome and count albums
 * show only what these back, so photos kept from another world or a Free Play run don't count here —
 * see {@link EarnedPhotos#visibleEntries}. Sent on join and respawn; each new biome or counted photo
 * after that arrives with its own photo ({@link EarnedPhotoPacket}).
 */
public record PhotoBiomesPacket(List<String> biomes, Map<String, Integer> counts) implements CustomPacketPayload {

    public static final Type<PhotoBiomesPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "photo_biomes"));

    public static final StreamCodec<FriendlyByteBuf, PhotoBiomesPacket> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), PhotoBiomesPacket::biomes,
        ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT), PhotoBiomesPacket::counts,
        PhotoBiomesPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound handler — only ever runs on the physical client. */
    public static void handle(PhotoBiomesPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            EarnedPhotos.setPhotographedBiomes(packet.biomes());
            EarnedPhotos.setPhotoCounts(packet.counts());
        });
    }
}
