package games.brennan.dungeontrain.fabric;

import com.mojang.logging.LogUtils;
import java.util.Collections;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;

/**
 * Resilient DtEvents firing for Fabric. A DT handler whose {@code :common} class references a
 * sibling-mod type (playermob / discordpresence / AIN / AIS / ECP) only in a METHOD BODY loads
 * and registers fine on Fabric-without-siblings, then throws a {@link LinkageError}
 * ({@code NoClassDefFoundError}) the first time it actually runs. On NeoForge those siblings are
 * always present so nothing ever throws.
 *
 * <p>{@link #fire}/{@link #fireCancellable} invoke each listener, and on the first
 * {@code LinkageError} <b>disable that listener</b> (identity-tracked) so it is skipped on every
 * subsequent fire — one warning, no per-tick exception storm. That feature is then inert (a
 * documented Fabric-v1 gap) while the rest of the core loop runs normally. Non-{@code LinkageError}
 * exceptions propagate unchanged (real bugs must not be swallowed).</p>
 */
public final class DtFire {

    private static final Logger LOG = LogUtils.getLogger();
    private static final Set<Object> DISABLED = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private DtFire() {}

    /** Fire an observe-only event: invoke every enabled listener, disabling any that hit a LinkageError. */
    public static <T> void fire(Iterable<T> listeners, Consumer<T> action) {
        for (T cb : listeners) {
            if (DISABLED.contains(cb)) {
                continue;
            }
            try {
                action.accept(cb);
            } catch (LinkageError e) {
                disable(cb, e);
            }
        }
    }

    /**
     * Fire a cancellable event: {@code action} returns {@code true} to cancel (stop-on-first-true,
     * matching the NeoForge bridges). A listener that hits a LinkageError is disabled and treated as
     * non-cancelling. Returns {@code true} if any listener cancelled.
     */
    public static <T> boolean fireCancellable(Iterable<T> listeners, Predicate<T> action) {
        for (T cb : listeners) {
            if (DISABLED.contains(cb)) {
                continue;
            }
            try {
                if (action.test(cb)) {
                    return true;
                }
            } catch (LinkageError e) {
                disable(cb, e);
            }
        }
        return false;
    }

    private static void disable(Object cb, LinkageError e) {
        DISABLED.add(cb);
        LOG.warn("Disabled a sibling-coupled DT handler on this loader ({}): {}", cb, e.toString());
    }
}
