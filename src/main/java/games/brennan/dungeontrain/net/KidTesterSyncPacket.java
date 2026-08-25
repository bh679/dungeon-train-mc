package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientKidTester;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: whether this player is a kid-safe tester, so the book vote page knows whether to
 * draw the red "Remove for kids" control (see {@code BookVoteClientEvents}).
 *
 * <p>The roster is the relay's ({@code kidtesters.js}), mirrored server-side in
 * {@link games.brennan.dungeontrain.event.KidTesterMirror}; this carries it the last hop to the page
 * that has to draw a button. Sent when the player's network consent lands
 * ({@link NetworkConsentSyncPacket}), which is both the earliest moment the fetch is permitted and
 * the moment a withdrawn consent must take the control away again.</p>
 *
 * <p>Mirror of {@link BookSuspensionSyncPacket}'s shape and its trust posture: a single boolean, and
 * advisory. A client that never receives this — an older jar, a player who declined network access,
 * a relay that has never heard of the roster — simply has no such control, which is where every
 * player stands today.</p>
 */
public record KidTesterSyncPacket(boolean tester) implements CustomPacketPayload {

    public static final Type<KidTesterSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "kid_tester_sync"));

    public static final StreamCodec<FriendlyByteBuf, KidTesterSyncPacket> STREAM_CODEC =
        StreamCodec.of(KidTesterSyncPacket::encode, KidTesterSyncPacket::decode);

    private static void encode(FriendlyByteBuf buf, KidTesterSyncPacket pkt) {
        buf.writeBoolean(pkt.tester());
    }

    private static KidTesterSyncPacket decode(FriendlyByteBuf buf) {
        return new KidTesterSyncPacket(buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(KidTesterSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientKidTester.set(packet.tester()));
    }
}
