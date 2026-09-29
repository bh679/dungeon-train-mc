package games.brennan.dungeontrain.util;

import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Logger names and the production/dev switch for DT's diagnostic logging.
 *
 * <p>FML's {@code log4j2.xml} runs the root logger at {@code all} with {@code debug.log} taking
 * DEBUG, so every DEBUG line any mod logs is written — synchronously, on the calling thread — for
 * every player, and {@code isDebugEnabled()} is always true unless a logger is capped explicitly.
 * DT caps the {@link #JITTER} namespace (its per-event physics/train probes) at INFO for players
 * and DEBUG in dev; the windowed summaries lag reports are read from ({@code [mspt]} and friends,
 * one line per 2 s) log on {@link #PERF}, which is left uncapped so they stay in players' logs.</p>
 */
public final class DtLogging {

    /** Per-event train/physics probes: DEBUG in dev, INFO for players (see {@link #jitterDebugEnabled}). */
    public static final String JITTER = "games.brennan.dungeontrain.jitter";

    /** Windowed perf summaries ({@code [mspt]}, {@code [gen.timing]}, ...): DEBUG everywhere. */
    public static final String PERF = "games.brennan.dungeontrain.perf";

    /** JVM property overriding the default: {@code -Ddungeontrain.jitterDebug=true|false}. */
    public static final String JITTER_DEBUG_PROPERTY = "dungeontrain.jitterDebug";

    private DtLogging() {}

    /** Whether the jitter namespace logs DEBUG in this JVM. */
    public static boolean jitterDebugEnabled() {
        return jitterDebugEnabled(System.getProperty(JITTER_DEBUG_PROPERTY), FMLEnvironment.production);
    }

    /** Pure core: an explicit property wins; otherwise on in dev ({@code runClient}/{@code runServer}), off in shipped jars. */
    static boolean jitterDebugEnabled(String property, boolean production) {
        if (property != null && !property.isBlank()) return Boolean.parseBoolean(property.trim());
        return !production;
    }
}
