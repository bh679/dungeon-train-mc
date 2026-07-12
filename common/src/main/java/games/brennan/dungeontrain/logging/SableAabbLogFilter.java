package games.brennan.dungeontrain.logging;

import com.mojang.logging.LogUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

/**
 * Suppresses ONE specific, high-frequency Sable log line without touching any other Sable logging.
 *
 * <p>Sable's {@code SubLevelInclusiveLevelEntityGetter.get(AABB, Consumer)} rejects any entity query
 * whose {@link net.minecraft.world.phys.AABB#getSize()} exceeds {@code MAX_GET_SIDE_LENGTH} (100000)
 * and, for every rejected call, logs at ERROR on logger {@code dev.ryanhcode.sable.Sable} with a
 * freshly-captured {@code new Throwable("Stack Trace")}:</p>
 *
 * <pre>Aborting entity get for abnormally large AABB: {} &lt;stack trace&gt;</pre>
 *
 * <p>When a Vivecraft player stands on a Sable sub-level (train carriage), Vivecraft's VR swing
 * tracker builds a {@code getEntities} AABB spanning the player's real position to the sub-level
 * pivot (~20 million blocks), so this fires ~15×/sec <em>on the render thread</em>. The per-call
 * stack-trace capture + write is the actual frame-hitching "lag" reported by players (FPS and heap
 * are fine — this is I/O stutter). See {@code SwingTrackerSubLevelAabbMixin} for the root-cause fix
 * when Vivecraft is present; this filter is the always-on belt so the storm can never resurface from
 * any other trigger (or any Vivecraft build the mixin can't apply to).</p>
 *
 * <p><b>Scope:</b> a message-specific {@link AbstractFilter} attached to the
 * {@code dev.ryanhcode.sable.Sable} {@link LoggerConfig}. It returns {@link Filter.Result#DENY} only
 * for records whose format matches the abort message and {@link Filter.Result#NEUTRAL} for everything
 * else, so <em>all other Sable logging is untouched</em> — Sable's log level is NOT raised.</p>
 */
public final class SableAabbLogFilter extends AbstractFilter {

    /** Logger name Sable's abort uses ({@code Sable.LOGGER}, created via {@code LogUtils.getLogger()}). */
    private static final String SABLE_LOGGER_NAME = "dev.ryanhcode.sable.Sable";

    /**
     * Distinctive substring of the abort's format pattern
     * ({@code "Aborting entity get for abnormally large AABB: {}"}). Matched against the format
     * pattern (cheap — no {@code {}} substitution of the giant AABB) so the filter cost is trivial.
     */
    private static final String ABORT_MARKER = "abnormally large AABB";

    private static final SableAabbLogFilter INSTANCE = new SableAabbLogFilter();

    /** Package-private so {@code SableAabbLogFilterTest} can exercise the {@code filter(...)} hooks. */
    SableAabbLogFilter() {
        // Start the filter so its lifecycle state is STARTED once attached. The filter() methods
        // do not gate on started-ness, but keeping the lifecycle correct avoids surprises if log4j
        // ever inspects it.
        super.start();
    }

    /**
     * Attach the filter to the {@code dev.ryanhcode.sable.Sable} logger. Idempotent and defensive:
     * any failure (unexpected log4j backend, already installed) is swallowed with a warning — the
     * game must never fail to start because a diagnostic-suppression filter could not be wired up.
     * Call once, early (mod construction).
     */
    public static void install() {
        try {
            if (!(LogManager.getContext(false) instanceof LoggerContext ctx)) {
                // Not the log4j-core backend (shouldn't happen under Minecraft) — nothing to do.
                return;
            }
            Configuration config = ctx.getConfiguration();
            LoggerConfig existing = config.getLoggerConfig(SABLE_LOGGER_NAME);

            if (SABLE_LOGGER_NAME.equals(existing.getName())) {
                if (containsInstance(existing)) {
                    return; // already installed (e.g. double init)
                }
                existing.addFilter(INSTANCE);
            } else {
                // No dedicated LoggerConfig for the Sable logger yet — create one that inherits the
                // effective level and stays additive (additivity=true), so events still reach the
                // root appenders exactly as before; only our message-specific DENY is added.
                LoggerConfig sableConfig =
                        new LoggerConfig(SABLE_LOGGER_NAME, existing.getLevel(), true);
                sableConfig.addFilter(INSTANCE);
                config.addLogger(SABLE_LOGGER_NAME, sableConfig);
            }
            ctx.updateLoggers();
            LogUtils.getLogger().info(
                    "[DungeonTrain] Installed Sable large-AABB log-spam filter on '{}'.",
                    SABLE_LOGGER_NAME);
        } catch (Throwable t) {
            LogUtils.getLogger().warn(
                    "[DungeonTrain] Could not install Sable large-AABB log-spam filter; "
                            + "the abort message may still spam if triggered.", t);
        }
    }

    private static boolean containsInstance(LoggerConfig cfg) {
        Filter f = cfg.getFilter();
        return f == INSTANCE || (f != null && f.toString().contains(SableAabbLogFilter.class.getName()));
    }

    private static Filter.Result decide(String format) {
        return format != null && format.contains(ABORT_MARKER) ? Filter.Result.DENY : Filter.Result.NEUTRAL;
    }

    // --- Filter hooks. Cover every path a LoggerConfig filter can be consulted through. ---

    @Override
    public Filter.Result filter(LogEvent event) {
        Message m = event == null ? null : event.getMessage();
        return m == null ? Filter.Result.NEUTRAL : decide(m.getFormat());
    }

    @Override
    public Filter.Result filter(org.apache.logging.log4j.core.Logger logger, Level level, Marker marker,
                                Message msg, Throwable t) {
        return msg == null ? Filter.Result.NEUTRAL : decide(msg.getFormat());
    }

    @Override
    public Filter.Result filter(org.apache.logging.log4j.core.Logger logger, Level level, Marker marker,
                                String msg, Object... params) {
        return decide(msg);
    }

    @Override
    public Filter.Result filter(org.apache.logging.log4j.core.Logger logger, Level level, Marker marker,
                                Object msg, Throwable t) {
        return decide(msg == null ? null : msg.toString());
    }
}
