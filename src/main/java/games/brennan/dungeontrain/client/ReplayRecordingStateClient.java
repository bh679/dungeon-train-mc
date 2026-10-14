package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.ReplayModRecordingProbe;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.ReplayRecordingStatePacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Tells the server when this client starts or stops recording with ReForgedPlay, so a
 * dedicated server can route Sable's train-movement snapshots into the replay
 * ({@code ship.sable.ReplayRecordingPlayers}).
 *
 * <p>Polls {@link ReplayModRecordingProbe#isRecordingLocally()} once a second and sends
 * {@link ReplayRecordingStatePacket} only when the answer changes, plus once after each join so
 * a new server learns the state. Nothing is sent during replay playback — the replay's
 * connection is not a server — and nothing at all happens without ReForgedPlay installed.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ReplayRecordingStateClient {

    /** Ticks between probes; the state changes on a user click, so once a second is plenty. */
    static final int POLL_INTERVAL_TICKS = 20;

    private static int ticksUntilPoll;
    /** The last value sent to the current connection; null until the first send after a join. */
    private static Boolean lastSent;

    private ReplayRecordingStateClient() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        lastSent = null;
        ticksUntilPoll = 0;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        lastSent = null;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ReplayModRecordingProbe.isPresent()) return;
        if (--ticksUntilPoll > 0) return;
        ticksUntilPoll = POLL_INTERVAL_TICKS;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        if (ReplayModRecordingProbe.isReplaying()) return;

        boolean recording = ReplayModRecordingProbe.isRecordingLocally();
        if (lastSent != null && lastSent == recording) return;
        lastSent = recording;
        DungeonTrainNet.sendToServer(new ReplayRecordingStatePacket(recording));
    }
}
