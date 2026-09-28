package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.streamdetect.api.StreamDetect;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Bridge into the optional <b>Stream Detect</b> add-on ({@code streamdetect}), which answers "is OBS
 * or Streamlabs open?" for the Videos icon's share tab and the Videos page's Submit pulse.
 *
 * <p><b>Why the detection is not in DT.</b> It reads the running-process table. That is the kind of
 * thing that can send a mod into CurseForge's manual review, and DT releases many times a week — so
 * the reading lives in its own small mod, reviewed once on its own listing, and DT only shows the UI
 * when it answers yes. For now it is jarJar'd inside DT on every platform (hybrid, like Keep Trim);
 * once its CurseForge listing is live, CurseForge installs it as its own mod instead.</p>
 *
 * <p>Same method names as the add-on's API. Without the add-on (or with one whose API no longer
 * links) every answer is "probe done, nothing found": {@link #hasResult()} is true and
 * {@link #detectNow()} is null, so callers need no special case. Hard imports are confined to the
 * nested {@link Api} holder, which is only loaded once the mod is known to be present.</p>
 */
public final class StreamDetectBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String MOD_ID = "streamdetect";

    /** Read once; the mod list does not change after loading. Cleared if the API fails to link. */
    private static volatile boolean present = ModList.get().isLoaded(MOD_ID);

    private StreamDetectBridge() {}

    /** Streaming software's process name if it is running, else null. Blocking on first call. */
    public static String detectNow() {
        if (!present) return null;
        try {
            return Api.detectNow();
        } catch (LinkageError e) {
            disable(e);
            return null;
        }
    }

    /** Whether there is an answer yet; always true without the add-on (the answer is "no"). */
    public static boolean hasResult() {
        if (!present) return true;
        try {
            return Api.hasResult();
        } catch (LinkageError e) {
            disable(e);
            return true;
        }
    }

    /** Non-blocking: true only once the probe has run and found something. */
    public static boolean isRunningNow() {
        if (!present) return false;
        try {
            return Api.isRunningNow();
        } catch (LinkageError e) {
            disable(e);
            return false;
        }
    }

    private static void disable(LinkageError e) {
        present = false;
        LOGGER.warn("[DungeonTrain] Stream Detect is installed but its API did not link — ignoring it", e);
    }

    /** The only place the add-on's classes are named. */
    private static final class Api {
        static String detectNow() { return StreamDetect.detectNow(); }
        static boolean hasResult() { return StreamDetect.hasResult(); }
        static boolean isRunningNow() { return StreamDetect.isRunningNow(); }
    }
}
