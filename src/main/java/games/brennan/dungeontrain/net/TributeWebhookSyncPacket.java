package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.discord.TributeWebhook;
import games.brennan.dungeontrain.discord.TributeWebhookMirror;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: this player's Tribute webhook ({@link TributeWebhook}), or {@code ""} for none. Sent on
 * login and whenever the player saves or removes it in Options. The server keeps it in memory only
 * ({@link TributeWebhookMirror}), and only if it is a Discord webhook link.
 */
public record TributeWebhookSyncPacket(String url) implements CustomPacketPayload {

    public static final Type<TributeWebhookSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "tribute_webhook_sync"));

    public static final StreamCodec<FriendlyByteBuf, TributeWebhookSyncPacket> STREAM_CODEC =
        StreamCodec.of(TributeWebhookSyncPacket::encode, TributeWebhookSyncPacket::decode);

    private static void encode(FriendlyByteBuf buf, TributeWebhookSyncPacket pkt) {
        String url = pkt.url() == null ? "" : pkt.url();
        buf.writeUtf(url.length() > TributeWebhook.MAX_LENGTH ? "" : url, TributeWebhook.MAX_LENGTH);
    }

    private static TributeWebhookSyncPacket decode(FriendlyByteBuf buf) {
        return new TributeWebhookSyncPacket(buf.readUtf(TributeWebhook.MAX_LENGTH));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TributeWebhookSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                TributeWebhookMirror.set(player, packet.url());
            }
        });
    }
}
