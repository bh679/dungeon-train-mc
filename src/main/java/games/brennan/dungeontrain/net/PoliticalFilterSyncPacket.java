package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.PoliticalFilterMirror;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player's Political Filter preference, sent on login and again whenever they
 * change it while connected.
 *
 * <p>The preference is a CLIENT-scope config ({@code ClientDisplayConfig.POLITICAL_FILTER}) but the
 * decision it drives is made server-side — which community book a chest placeholder becomes is
 * resolved on the server when the stack first reaches a hand. So the client has to tell the server,
 * the same way {@link NetworkConsentSyncPacket} does for network consent.</p>
 *
 * <p>Carries the RESOLVED boolean, not the tri-state: {@code UNSET} means different things for
 * different players (see {@code PoliticalFilterPrefs}), and resolving it once on the client keeps
 * that rule in one place instead of duplicated across the wire.</p>
 */
public record PoliticalFilterSyncPacket(boolean enabled) implements CustomPacketPayload {

    public static final Type<PoliticalFilterSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "political_filter_sync"));

    public static final StreamCodec<FriendlyByteBuf, PoliticalFilterSyncPacket> STREAM_CODEC =
        StreamCodec.of(PoliticalFilterSyncPacket::encode, PoliticalFilterSyncPacket::decode);

    private static void encode(FriendlyByteBuf buf, PoliticalFilterSyncPacket pkt) {
        buf.writeBoolean(pkt.enabled());
    }

    private static PoliticalFilterSyncPacket decode(FriendlyByteBuf buf) {
        return new PoliticalFilterSyncPacket(buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PoliticalFilterSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            PoliticalFilterMirror.set(player, packet.enabled());
        });
    }
}
