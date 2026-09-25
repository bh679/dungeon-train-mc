package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dpibypassdetect.api.DpiBypassDetect;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Bridge into the optional <b>DPI Bypass Detect</b> add-on ({@code dpibypassdetect}), which answers
 * "is zapret / GoodbyeDPI running?" for {@code client.DpiBypassPromptHandler}'s title-screen warning
 * that the relay may be silently blocked.
 *
 * <p>The detection reads the running-process table, so — like {@link StreamDetectBridge} — it lives
 * in its own small mod. For now it is jarJar'd inside DT on every platform (hybrid, like Keep Trim);
 * once its CurseForge listing is live, CurseForge installs it as its own mod instead.</p>
 *
 * <p>Without the add-on (or with one whose API no longer links) every answer is "probe done,
 * nothing found": {@link #hasResult()} is true and {@link #detectNow()} is null. Hard imports are
 * confined to the nested {@link Api} holder.</p>
 */
public final class DpiBypassDetectBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String MOD_ID = "dpibypassdetect";

    /** Read once; the mod list does not change after loading. Cleared if the API fails to link. */
    private static volatile boolean present = ModList.get().isLoaded(MOD_ID);

    private DpiBypassDetectBridge() {}

    /** The bypass tool's process name if one is running, else null. Blocking on first call. */
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

    private static void disable(LinkageError e) {
        present = false;
        LOGGER.warn("[DungeonTrain] DPI Bypass Detect is installed but its API did not link — ignoring it", e);
    }

    /** The only place the add-on's classes are named. */
    private static final class Api {
        static String detectNow() { return DpiBypassDetect.detectNow(); }
        static boolean hasResult() { return DpiBypassDetect.hasResult(); }
    }
}
