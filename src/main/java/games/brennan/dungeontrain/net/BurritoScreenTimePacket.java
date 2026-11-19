package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.BurritoClock;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player just left a pausing screen that is not the pause menu (a book, the photo
 * viewer, a DT popup) after {@code ms} of wall-clock time on it. That time counts toward the Everything
 * Burrito ({@link BurritoClock#addPlayTicks}).
 *
 * <p>Measured on the client because a single-player server stands still behind a pause screen and holds
 * packets until it ends, so it cannot time one. The server's own burrito ticking
 * ({@code BurritoPlayTimeEvents}) stops for every pause screen, so this is the only credit those screens
 * get.</p>
 *
 * <p>Not a trust concern: a spoofed packet only makes the sender's own burrito slower, and the fastest
 * time wins. One screen is still capped at {@link #MAX_MS}.</p>
 */
public record BurritoScreenTimePacket(long ms) implements CustomPacketPayload {

    /** The most one screen can credit (24 h). */
    public static final long MAX_MS = 86_400_000L;

    /** Wall-clock milliseconds per server tick. */
    private static final long MS_PER_TICK = 50L;

    public static final Type<BurritoScreenTimePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "burrito_screen_time"));

    public static final StreamCodec<FriendlyByteBuf, BurritoScreenTimePacket> STREAM_CODEC =
        StreamCodec.of(BurritoScreenTimePacket::encode, BurritoScreenTimePacket::decode);

    private static void encode(FriendlyByteBuf buf, BurritoScreenTimePacket p) {
        buf.writeVarLong(p.ms);
    }

    private static BurritoScreenTimePacket decode(FriendlyByteBuf buf) {
        return new BurritoScreenTimePacket(buf.readVarLong());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Ticks to credit for {@code ms} on a screen: clamped to {@code [0, MAX_MS]}, then whole ticks. */
    public static long ticksFor(long ms) {
        return Math.max(0L, Math.min(MAX_MS, ms)) / MS_PER_TICK;
    }

    public static void handle(BurritoScreenTimePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!player.isAlive() || RunIntegrity.isCheated(player)) return;
            BurritoClock.addPlayTicks(player.getUUID(), ticksFor(packet.ms));
        });
    }
}
