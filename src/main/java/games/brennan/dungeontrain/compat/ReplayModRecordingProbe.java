package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Asks <b>ReForgedPlay</b> (the NeoForge port of Replay Mod, modId {@code reforgedplaymod}) whether
 * it is currently recording this client's connection, or playing a replay back.
 *
 * <p>Why DT cares: Replay Mod only records packets that pass through the vanilla
 * {@code Connection}. Sable sends its per-tick sub-level pose snapshots outside it (singleplayer:
 * a direct drop onto the client's network event loop; multiplayer: its own UDP socket), so a
 * replay of a train ride holds the carriages' first pose and nothing after — the train stands
 * still while the recorded player walks away from it. While {@link #isRecordingLocally()} is true
 * {@code mixin.SubLevelTrackingOrderedSnapshotMixin} answers "not UDP-connected" for the recording
 * player, which makes Sable take its ordered-connection fallback — a bundle Replay Mod does
 * record and replays through Sable's normal client handler. See
 * {@code ship.sable.ReplayRecordingPlayers} for the dedicated-server half.</p>
 *
 * <p>ReForgedPlay is reached by reflection only — it is a client-only mod with no Maven artifact,
 * and it is absent on every dedicated server. The class names are ReForgedPlay 1.21.1-0.3's
 * ({@code com.replaymod.recording.ReplayModRecording} / {@code com.replaymod.replay.ReplayModReplay},
 * the same public statics the upstream Replay Mod has carried since 1.12). A recorder's
 * {@code ConnectionEventHandler.getPacketListener()} is non-null from the moment the recording
 * packet listener attaches to the connection, which is how "is this session being recorded" is
 * read. If any name fails to resolve the probe logs once, latches to {@code false}, and the
 * train keeps running exactly as before — nothing here may throw into Sable's tracking tick.</p>
 */
public final class ReplayModRecordingProbe {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String MOD_ID = "reforgedplaymod";

    private static final String RECORDING_CLASS = "com.replaymod.recording.ReplayModRecording";
    private static final String CONNECTION_HANDLER_CLASS = "com.replaymod.recording.handler.ConnectionEventHandler";
    private static final String REPLAY_CLASS = "com.replaymod.replay.ReplayModReplay";

    /** Read once; the mod list does not change after loading. */
    private static final boolean PRESENT = detectPresent();

    private static volatile Reflection recording;
    private static volatile Reflection replay;

    private ReplayModRecordingProbe() {}

    /** Whether ReForgedPlay is installed on this side at all. */
    public static boolean isPresent() {
        return PRESENT;
    }

    /**
     * True while ReForgedPlay has a recorder attached to this client's current connection.
     * Always false without the mod, on a dedicated server, and after a reflection failure.
     */
    public static boolean isRecordingLocally() {
        if (!PRESENT) return false;
        Reflection r = recordingReflection();
        if (r == null) return false;
        try {
            Object module = r.instance.get(null);
            if (module == null) return false;
            Object handler = r.first.invoke(module);
            if (handler == null) return false;
            return r.second.invoke(handler) != null;
        } catch (Throwable t) {
            fail("recording", t);
            return false;
        }
    }

    /**
     * True while ReForgedPlay is playing a replay back (an active replay handler exists).
     * Always false without the mod, on a dedicated server, and after a reflection failure.
     */
    public static boolean isReplaying() {
        if (!PRESENT) return false;
        Reflection r = replayReflection();
        if (r == null) return false;
        try {
            Object module = r.instance.get(null);
            if (module == null) return false;
            return r.first.invoke(module) != null;
        } catch (Throwable t) {
            fail("playback", t);
            return false;
        }
    }

    private static boolean detectPresent() {
        try {
            return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
        } catch (Throwable t) {
            // Loader state unreadable (unit tests, very early init): the feature stays idle.
            return false;
        }
    }

    private static Reflection recordingReflection() {
        Reflection r = recording;
        if (r != null) return r.failed ? null : r;
        try {
            Class<?> module = Class.forName(RECORDING_CLASS);
            Class<?> handler = Class.forName(CONNECTION_HANDLER_CLASS);
            r = new Reflection(
                module.getField("instance"),
                module.getMethod("getConnectionEventHandler"),
                handler.getMethod("getPacketListener"));
        } catch (Throwable t) {
            r = Reflection.FAILED;
            fail("recording", t);
        }
        recording = r;
        return r.failed ? null : r;
    }

    private static Reflection replayReflection() {
        Reflection r = replay;
        if (r != null) return r.failed ? null : r;
        try {
            Class<?> module = Class.forName(REPLAY_CLASS);
            r = new Reflection(module.getField("instance"), module.getMethod("getReplayHandler"), null);
        } catch (Throwable t) {
            r = Reflection.FAILED;
            fail("playback", t);
        }
        replay = r;
        return r.failed ? null : r;
    }

    private static void fail(String what, Throwable t) {
        if ("recording".equals(what)) {
            if (recording == Reflection.FAILED) return;
            recording = Reflection.FAILED;
        } else {
            if (replay == Reflection.FAILED) return;
            replay = Reflection.FAILED;
        }
        LOGGER.warn("[DungeonTrain] ReForgedPlay is installed but its {} state could not be read — "
            + "Sable train movement will not be routed into replays ({})", what, t.toString());
    }

    /** Resolved handles; {@link #FAILED} is the latched "stop trying" marker. */
    private static final class Reflection {
        static final Reflection FAILED = new Reflection();

        final boolean failed;
        final Field instance;
        final Method first;
        final Method second;

        private Reflection() {
            this.failed = true;
            this.instance = null;
            this.first = null;
            this.second = null;
        }

        Reflection(Field instance, Method first, Method second) {
            this.failed = false;
            this.instance = instance;
            this.first = first;
            this.second = second;
        }
    }
}
