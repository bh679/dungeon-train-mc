package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.PlayerLocaleMirror;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: seed the server's per-player language mirror from the client's selected game
 * language ({@code Minecraft.getInstance().options.languageCode}, e.g. {@code "en_us"},
 * {@code "de_de"}, {@code "pt_br"}), sent on login (and again if the client's language changes while
 * connected — reserved for a future re-sync; login is sufficient today).
 *
 * <p>The language is a CLIENT-scope setting the server can't otherwise read, so the client must tell
 * it. The server keeps a per-session mirror ({@link PlayerLocaleMirror}) so player-written content
 * uploaded server-side (community shared books via {@code SharedBookReporter}, lectern letters via
 * {@code LetterReporter}) can be stamped with the author's language for language-matched delivery.</p>
 *
 * <p>Mirror of {@link NetworkConsentSyncPacket}'s shape — a single payload field, its own {@link Type}
 * id under the mod namespace, and a {@link StreamCodec} built from encode/decode. Carries a
 * {@code String} (the language code) rather than a boolean.</p>
 */
public record PlayerLocaleSyncPacket(String langCode) implements CustomPacketPayload {

    public static final Type<PlayerLocaleSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "player_locale_sync"));

    public static final StreamCodec<FriendlyByteBuf, PlayerLocaleSyncPacket> STREAM_CODEC =
        StreamCodec.of(PlayerLocaleSyncPacket::encode, PlayerLocaleSyncPacket::decode);

    private static void encode(FriendlyByteBuf buf, PlayerLocaleSyncPacket pkt) {
        buf.writeUtf(pkt.langCode() == null ? "" : pkt.langCode());
    }

    private static PlayerLocaleSyncPacket decode(FriendlyByteBuf buf) {
        return new PlayerLocaleSyncPacket(buf.readUtf());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PlayerLocaleSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            PlayerLocaleMirror.set(player, packet.langCode());
        });
    }
}
