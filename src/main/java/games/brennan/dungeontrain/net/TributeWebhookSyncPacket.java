package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.discord.TributeWebhook;
import games.brennan.dungeontrain.discord.TributeWebhookMirror;
import games.brennan.dungeontrain.discord.WebhookLink;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Client → server: this player's own Discord webhooks ({@link TributeWebhook}) and the feeds each gets,
 * possibly none. Sent on login and whenever the player changes them in Options. Names stay on the client. The server keeps them in memory only
 * ({@link TributeWebhookMirror}), and only the valid ones, at most {@link TributeWebhook#MAX_LINKS}.
 */
public record TributeWebhookSyncPacket(List<WebhookLink> links) implements CustomPacketPayload {

    public static final Type<TributeWebhookSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "tribute_webhook_sync"));

    public static final StreamCodec<FriendlyByteBuf, TributeWebhookSyncPacket> STREAM_CODEC =
        StreamCodec.of(TributeWebhookSyncPacket::encode, TributeWebhookSyncPacket::decode);

    public TributeWebhookSyncPacket {
        links = links == null ? List.of() : List.copyOf(links);
    }

    private static void encode(FriendlyByteBuf buf, TributeWebhookSyncPacket pkt) {
        List<WebhookLink> links = WebhookLink.cleanList(pkt.links());
        buf.writeVarInt(links.size());
        for (WebhookLink link : links) {
            buf.writeUtf(link.url(), TributeWebhook.MAX_LENGTH);
            buf.writeVarInt(link.feeds());
        }
    }

    private static TributeWebhookSyncPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > TributeWebhook.MAX_LINKS) {
            throw new IllegalArgumentException("Too many tribute webhooks: " + count);
        }
        List<WebhookLink> links = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String url = buf.readUtf(TributeWebhook.MAX_LENGTH);
            links.add(new WebhookLink(url, buf.readVarInt()));
        }
        return new TributeWebhookSyncPacket(links);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TributeWebhookSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                TributeWebhookMirror.set(player, packet.links());
            }
        });
    }
}
