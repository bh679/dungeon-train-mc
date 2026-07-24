package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.ShotUploadClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Client → server: the death screen's full tagged ride-photo gallery, sent once when the
 * {@code NarrativeDeathScreen} opens. Distinct from {@link DeathPhotoPacket} (which sends the single
 * SCENIC fall photo for the Discord death-report embed) — this carries every death-screen photo with
 * its per-photo facets so the server can archive the whole tagged set to the relay's Photos page via
 * {@link ShotUploadClient}. Each entry is a JPEG (≤1080px, well under the per-photo cap) plus its
 * {@code SnapshotTag} name and the biome/band/difficulty/cart sampled at capture.
 */
public record RideGalleryPacket(List<Photo> photos) implements CustomPacketPayload {

    /** One tagged photo: facets (empty strings / 0 when unknown) + JPEG bytes. */
    public record Photo(String tag, String biome, String band, int difficulty, int cart, String gfx, byte[] jpeg) {}

    /** Per-photo JPEG cap the codec enforces (RideSnapshot#photoBytes keeps real shots well under). */
    private static final int MAX_JPEG = 2 * 1024 * 1024;
    /** Hard cap on photo count (defends decode against a hostile count; the gallery sends ≈5). */
    private static final int MAX_PHOTOS = 8;
    private static final int MAX_STR = 200;

    public static final Type<RideGalleryPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "ride_gallery"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RideGalleryPacket> STREAM_CODEC =
            StreamCodec.of(RideGalleryPacket::encode, RideGalleryPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buf, RideGalleryPacket p) {
        int count = Math.min(p.photos().size(), MAX_PHOTOS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            Photo ph = p.photos().get(i);
            buf.writeUtf(ph.tag() == null ? "" : ph.tag());
            buf.writeUtf(ph.biome() == null ? "" : ph.biome());
            buf.writeUtf(ph.band() == null ? "" : ph.band());
            buf.writeVarInt(ph.difficulty());
            buf.writeVarInt(ph.cart());
            buf.writeUtf(ph.gfx() == null ? "" : ph.gfx());
            buf.writeByteArray(ph.jpeg());
        }
    }

    private static RideGalleryPacket decode(RegistryFriendlyByteBuf buf) {
        int count = Math.min(Math.max(0, buf.readVarInt()), MAX_PHOTOS);
        List<Photo> photos = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String tag = buf.readUtf(MAX_STR);
            String biome = buf.readUtf(MAX_STR);
            String band = buf.readUtf(MAX_STR);
            int difficulty = buf.readVarInt();
            int cart = buf.readVarInt();
            String gfx = buf.readUtf(MAX_STR);
            byte[] jpeg = buf.readByteArray(MAX_JPEG);
            photos.add(new Photo(tag, biome, band, difficulty, cart, gfx, jpeg));
        }
        return new RideGalleryPacket(List.copyOf(photos));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RideGalleryPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            // Off-thread relay uploads (one multipart POST per photo); best-effort, never blocks the tick.
            ShotUploadClient.uploadGallery(player.getUUID(), packet.photos());
        });
    }
}
