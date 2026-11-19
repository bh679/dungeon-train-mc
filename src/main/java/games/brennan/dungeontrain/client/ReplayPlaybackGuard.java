package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.compat.ReplayModRecordingProbe;
import org.slf4j.Logger;

/**
 * Keeps DT's camera cinematics out of ReForgedPlay (Replay Mod) playback.
 *
 * <p>A replay plays back the recorded login packets, DT's {@code CinematicPreloadBeginPacket} and
 * {@code CinematicIntroPacket} included, so without this the viewer re-ran the spawn intro: the
 * preload gate forced its loading screen over Replay Mod's screens every tick, and the parked
 * preview pose ({@code CameraCinematicMixin}) overwrote Replay Mod's camera every frame. A render
 * job restarts the replay, nothing pressed Space, and every rendered frame was the intro's opening
 * shot. The intro is a client-side camera effect that was never in the recording, so playback
 * loses nothing by skipping it.</p>
 */
public final class ReplayPlaybackGuard {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean loggedSkip;

    private ReplayPlaybackGuard() {}

    /**
     * True while a replay is playing, in which case the caller must not start {@code what}.
     * Logs the first skip of the session.
     */
    public static boolean skipsCinematic(String what) {
        if (!ReplayModRecordingProbe.isReplaying()) return false;
        if (!loggedSkip) {
            loggedSkip = true;
            LOGGER.info("[DungeonTrain] Replay playback: skipped the recorded {} so Replay Mod keeps its camera", what);
        }
        return true;
    }
}
