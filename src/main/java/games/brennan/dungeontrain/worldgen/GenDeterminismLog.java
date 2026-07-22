package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Flag-gated determinism diagnostics for the worldgen block pipeline.
 *
 * <p>Enable with {@code -Ddungeontrain.genDeterminismLog=true}. Every line is emitted as
 * {@code [gen.det] <tag> …} at INFO and MUST be self-keyed by chunk/world coordinates —
 * parallel worldgen threads interleave differently per run, so consumers sort the lines
 * and diff two runs' sorted sets rather than relying on emission order.
 *
 * <p>Used by the same-seed twin-run harness to pinpoint which decoration/stamping input
 * varies between runs (band context snapshot, biome lists, stairs reservations, corridor
 * sweep coverage). Zero cost when disabled: the flag is a {@code static final boolean},
 * so guarded call sites are dead-code eliminated.
 */
public final class GenDeterminismLog {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** True when {@code -Ddungeontrain.genDeterminismLog=true} was passed to the JVM. */
    public static final boolean ENABLED = Boolean.getBoolean("dungeontrain.genDeterminismLog");

    private GenDeterminismLog() {}

    /** Emit one sorted-diffable diagnostic line: {@code [gen.det] <tag> <formatted>}. */
    public static void log(String tag, String format, Object... args) {
        if (!ENABLED) return;
        LOGGER.info("[gen.det] {} {}", tag, String.format(format, args));
    }
}
