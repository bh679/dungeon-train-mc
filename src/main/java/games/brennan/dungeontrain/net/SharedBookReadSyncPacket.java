package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.SharedBookReadMirror;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Client → server: seed / top up the server's per-player community-book read mirror
 * ({@link SharedBookReadMirror}) from the client's GLOBAL read history
 * ({@code ClientDisplayConfig.readSharedIds()}). Sent on login with the full set, and again with a single
 * new id each time the player finishes reading a community book (see {@code SharedBookReadSyncClient} and
 * {@code BookReadClientEvents}).
 *
 * <p>The client is the authoritative durable store — the read history is a CLIENT-scope config that follows
 * the player across worlds and servers — so a login sync is the only way the server learns it. The mirror
 * is the fallback source for the shared-book loot selector's unread-first step when the relay can't
 * personalise the pool (older relay, no network consent, or offline).</p>
 *
 * <p><b>Deliberately NOT network-consent-gated</b>, unlike {@link BookReadClosedPacket}: it carries only
 * public relay pool ids (loot identifiers the server already handed the client), never leaves the server —
 * it feeds an in-memory mirror, not the relay — and must work for a player who declined telemetry, since
 * that is exactly the case the client-side fallback exists to cover.</p>
 *
 * <p>Mirror of {@link NetworkConsentSyncPacket}'s shape — its own {@link Type} id under the mod namespace
 * and a {@link StreamCodec} built from encode/decode — but the payload is a var-int list of pool ids.</p>
 */
public record SharedBookReadSyncPacket(List<Integer> ids) implements CustomPacketPayload {

    /** Defensive bound on a single payload so a corrupt/hostile client can't force an unbounded read. */
    private static final int MAX_IDS = 100_000;

    public static final Type<SharedBookReadSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "shared_book_read_sync"));

    public static final StreamCodec<FriendlyByteBuf, SharedBookReadSyncPacket> STREAM_CODEC =
        StreamCodec.of(SharedBookReadSyncPacket::encode, SharedBookReadSyncPacket::decode);

    private static void encode(FriendlyByteBuf buf, SharedBookReadSyncPacket pkt) {
        List<Integer> ids = pkt.ids();
        int n = Math.min(ids.size(), MAX_IDS);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) buf.writeVarInt(ids.get(i));
    }

    private static SharedBookReadSyncPacket decode(FriendlyByteBuf buf) {
        int n = Math.min(Math.max(0, buf.readVarInt()), MAX_IDS);
        List<Integer> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ids.add(buf.readVarInt());
        return new SharedBookReadSyncPacket(ids);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SharedBookReadSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            SharedBookReadMirror.add(player, packet.ids());
        });
    }
}
