package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.ReadingLanguagesMirror;
import games.brennan.dungeontrain.narrative.ReadingLanguages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Client → server: the player's reading-language settings (game-language weight + extra languages), sent
 * on login and whenever they change. Stored in {@link ReadingLanguagesMirror}; the server re-cleans
 * everything, so the client's values are only ever a request.
 */
public record ReadingLanguagesSyncPacket(int gameWeight, List<ReadingLanguages.Entry> extras)
        implements CustomPacketPayload {

    public static final Type<ReadingLanguagesSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "reading_languages_sync"));

    public static final StreamCodec<FriendlyByteBuf, ReadingLanguagesSyncPacket> STREAM_CODEC =
        StreamCodec.of(ReadingLanguagesSyncPacket::encode, ReadingLanguagesSyncPacket::decode);

    private static void encode(FriendlyByteBuf buf, ReadingLanguagesSyncPacket pkt) {
        buf.writeVarInt(pkt.gameWeight());
        int n = Math.min(pkt.extras().size(), ReadingLanguages.MAX_EXTRA);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            ReadingLanguages.Entry e = pkt.extras().get(i);
            buf.writeUtf(e.locale(), ReadingLanguages.MAX_LOCALE_LENGTH_WIRE);
            buf.writeVarInt(e.weight());
        }
    }

    private static ReadingLanguagesSyncPacket decode(FriendlyByteBuf buf) {
        int gameWeight = buf.readVarInt();
        int n = buf.readVarInt();
        if (n < 0 || n > ReadingLanguages.MAX_EXTRA) {
            throw new IllegalArgumentException("too many reading languages: " + n);
        }
        List<ReadingLanguages.Entry> extras = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            extras.add(new ReadingLanguages.Entry(buf.readUtf(ReadingLanguages.MAX_LOCALE_LENGTH_WIRE), buf.readVarInt()));
        }
        return new ReadingLanguagesSyncPacket(gameWeight, List.copyOf(extras));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ReadingLanguagesSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            ReadingLanguagesMirror.set(player, packet.gameWeight(), packet.extras());
        });
    }
}
