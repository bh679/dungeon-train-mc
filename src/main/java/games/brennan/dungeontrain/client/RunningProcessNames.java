package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One session-cached snapshot of the <b>names</b> of the processes running on this machine, shared
 * by every detector that wants to know whether some particular program is open —
 * {@link DpiBypassDetector} (is a DPI-bypass tool mangling our traffic?) and
 * {@link StreamingSoftwareDetector} (is OBS up, so the player might have a stream to share?).
 *
 * <p>Shared so the process table is enumerated <b>once</b>, not once per detector: the enumeration
 * costs tens of milliseconds, and the answer is not going to change in a way worth re-reading it
 * for. Names only. No command line, no arguments, no elevation, no file or registry key — and the
 * snapshot never leaves this class except to a detector's {@code matchIn}. Each detector's Javadoc
 * says what it looks for; this is the whole of how it looks.</p>
 *
 * <p>Enumeration goes through <b>OSHI</b>, which ships with Minecraft (vanilla {@code SystemReport}
 * uses it), so no new dependency — and it is the only option that works for the DPI case, where
 * the tool runs elevated and {@code ProcessHandle.allProcesses()} reports no command for it from a
 * medium-integrity JVM. Process names come from {@code NtQuerySystemInformation} on Windows and
 * from the platform equivalents elsewhere, all readable without elevation.</p>
 *
 * <p>Blocking; call it off the render thread ({@code Util.ioPool()}). Any failure reads as an empty
 * table: a courtesy that can't be produced safely is not worth producing.</p>
 */
public final class RunningProcessNames {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Session cache: immutable list of names, or null for "not yet taken". */
    private static List<String> cached = null;

    private RunningProcessNames() {}

    /** The snapshot, taken on first call and cached for the session. Never null, never throws. */
    public static synchronized List<String> snapshot() {
        if (cached == null) {
            cached = enumerate();
        }
        return cached;
    }

    /** Whether the snapshot has been taken this session, so callers can avoid blocking on it. */
    public static synchronized boolean hasSnapshot() {
        return cached != null;
    }

    /** Reads the live process table into an immutable list; anything unexpected reads as empty. */
    private static List<String> enumerate() {
        try {
            List<String> names = new ArrayList<>();
            for (oshi.software.os.OSProcess process
                    : new oshi.SystemInfo().getOperatingSystem().getProcesses()) {
                names.add(process.getName());
            }
            return List.copyOf(names.stream().filter(n -> n != null).toList());
        } catch (Throwable t) {
            // Process enumeration is a best-effort courtesy, never a reason to disturb the client.
            LOGGER.debug("[DungeonTrain] Could not enumerate running processes", t);
            return List.of();
        }
    }

    /**
     * Lowercased final path segment of {@code raw}, handling both separators — what a process table
     * hands back differs by platform and by OSHI version, and a match should not care.
     */
    static String baseName(String raw) {
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        int cut = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
        return cut < 0 ? lower : lower.substring(cut + 1);
    }
}
