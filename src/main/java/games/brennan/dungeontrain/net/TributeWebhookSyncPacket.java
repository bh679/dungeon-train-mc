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

import java.util.ArrayList;
import java.util.List;

/**
 * Client → server: this player's Tribute webhooks ({@link TributeWebhook}), possibly none. Sent on login
 * and whenever the player adds or removes one in Options. The server keeps them in memory only
 * ({@link TributeWebhookMirror}), and only the valid ones, at most {@link TributeWebhook#MAX_LINKS}.
 */
public record TributeWebhookSyncPacket(List<String> urls) implements CustomPacketPayload {

    public static final Type<TributeWebhookSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "tribute_webhook_sync"));

    public static final StreamCodec<FriendlyByteBuf, TributeWebhookSyncPacket> STREAM_CODEC =
        StreamCodec.of(TributeWebhookSyncPacket::encode, TributeWebhookSyncPacket::decode);

    public TributeWebhookSyncPacket {
        urls = urls == null ? List.of() : List.copyOf(urls);
    }

    private static void encode(FriendlyByteBuf buf, TributeWebhookSyncPacket pkt) {
        List<String> links = TributeWebhook.cleanList(pkt.urls());
        buf.writeVarInt(links.size());
        for (String url : links) buf.writeUtf(url, TributeWebhook.MAX_LENGTH);
    }

    private static TributeWebhookSyncPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > TributeWebhook.MAX_LINKS) {
            throw new IllegalArgumentException("Too many tribute webhooks: " + count);
        }
        List<String> links = new ArrayList<>(count);
        for (int i = 0; i < count; i++) links.add(buf.readUtf(TributeWebhook.MAX_LENGTH));
        return new TributeWebhookSyncPacket(links);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TributeWebhookSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                TributeWebhookMirror.set(player, packet.urls());
            }
        });
    }
}
