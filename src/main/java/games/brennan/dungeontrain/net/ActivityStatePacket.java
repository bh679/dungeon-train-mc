package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VersionHudOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: whether the receiving player's time counters are running, and why not when they
 * are not. Drives the dev-HUD "Time:" read-out — the state is server-authoritative (the idle rules,
 * the carriage-progress window and the counters all live there), so the client cannot derive it.
 *
 * <p>{@code reason} is a {@link games.brennan.dungeontrain.event.PlayerActivityTracker.Reason}
 * ordinal. {@code stoppedSeconds} is how long the binding clock has been expired — the number the
 * HUD counts up — and is 0 while tracking or paused. {@code carriagesInWindow} is the traversal
 * span over the progress window, shown against the required minimum.</p>
 *
 * <p>Sent from the tracker's existing 10-tick scan and only when a displayed value changes, so a
 * frozen player generates no traffic at all.</p>
 */
public record ActivityStatePacket(boolean countingRun, boolean countingTrain, int reason,
                                  int stoppedSeconds, int carriagesInWindow,
                                  long trainTimeTicks, long runTicks) implements CustomPacketPayload {

    public static final Type<ActivityStatePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "activity_state"));

    public static final StreamCodec<FriendlyByteBuf, ActivityStatePacket> STREAM_CODEC =
        StreamCodec.of(ActivityStatePacket::encode, ActivityStatePacket::decode);

    private static void encode(FriendlyByteBuf buf, ActivityStatePacket p) {
        buf.writeBoolean(p.countingRun);
        buf.writeBoolean(p.countingTrain);
        buf.writeVarInt(p.reason);
        buf.writeVarInt(Math.max(0, p.stoppedSeconds));
        buf.writeVarInt(Math.max(0, p.carriagesInWindow));
        buf.writeVarLong(Math.max(0L, p.trainTimeTicks));
        buf.writeVarLong(Math.max(0L, p.runTicks));
    }

    private static ActivityStatePacket decode(FriendlyByteBuf buf) {
        boolean countingRun = buf.readBoolean();
        boolean countingTrain = buf.readBoolean();
        int reason = buf.readVarInt();
        int stoppedSeconds = buf.readVarInt();
        int carriagesInWindow = buf.readVarInt();
        long trainTimeTicks = buf.readVarLong();
        long runTicks = buf.readVarLong();
        return new ActivityStatePacket(countingRun, countingTrain, reason, stoppedSeconds,
            carriagesInWindow, trainTimeTicks, runTicks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ActivityStatePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> VersionHudOverlay.setActivityState(packet));
    }
}
