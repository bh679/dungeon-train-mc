package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.EnchiridionAdvancements;
import games.brennan.dungeontrain.compat.photo.SharedPhotoCriteria;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Client → server, at login: for each shared mob collection ({@link EnchiridionAdvancements#SHARED_PHOTO_LISTS},
 * by path), the mobs whose photo the client keeps in that collection's own album. Photos live only on the client,
 * so this is how the server learns which ticks have one ({@link SharedPhotoCriteria#onAlbumReport}).
 */
public record PhotoAlbumReportPacket(Map<String, Set<String>> photos) implements CustomPacketPayload {

    public static final Type<PhotoAlbumReportPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "photo_album_report"));

    /** Most Wanted lists well under this many mobs; anything past it is dropped. */
    static final int MAX_ENTRIES = 512;
    private static final int MAX_ID_LENGTH = 256;

    public static final StreamCodec<FriendlyByteBuf, PhotoAlbumReportPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> packet.encode(buf), PhotoAlbumReportPacket::decode);

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(photos.size());
        photos.forEach((list, ids) -> {
            buf.writeUtf(list, MAX_ID_LENGTH);
            buf.writeVarInt(ids.size());
            for (String id : ids) buf.writeUtf(id, MAX_ID_LENGTH);
        });
    }

    public static PhotoAlbumReportPacket decode(FriendlyByteBuf buf) {
        int lists = buf.readVarInt();
        if (lists < 0 || lists > EnchiridionAdvancements.SHARED_PHOTO_LISTS.size()) {
            throw new IllegalArgumentException("photo album report: " + lists + " lists");
        }
        Map<String, Set<String>> photos = new LinkedHashMap<>();
        for (int i = 0; i < lists; i++) {
            String list = buf.readUtf(MAX_ID_LENGTH);
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("photo album report: " + count + " entries");
            Set<String> ids = new HashSet<>();
            for (int j = 0; j < count; j++) ids.add(buf.readUtf(MAX_ID_LENGTH));
            photos.put(list, Set.copyOf(ids));
        }
        return new PhotoAlbumReportPacket(Map.copyOf(photos));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PhotoAlbumReportPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) SharedPhotoCriteria.onAlbumReport(player, packet.photos());
        });
    }
}
