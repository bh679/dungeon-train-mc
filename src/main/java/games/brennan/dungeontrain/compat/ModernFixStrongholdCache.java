package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Keeps ModernFix's stronghold-ring cache ({@code perf.cache_strongholds}) working now that DT defers
 * the overworld's ring search out of the {@code ServerLevel} constructor.
 *
 * <p>ModernFix wraps the constructor's {@code ensureStructuresGenerated()} call to record the
 * dimension folder and server on the {@code ChunkGeneratorStructureState}, and separately redirects
 * {@code Util.backgroundExecutor()} inside {@code generateRingPositions} to a thread pool it only
 * creates once that path is known. If the path was never recorded the redirect hands
 * {@code CompletableFuture.supplyAsync} a {@code null} executor — the "Exception generating new chunk"
 * NullPointerException that crashed (or stalled at 33%) every new world in v0.982.0. DT's
 * {@code ServerLevelDeferRingGenMixin} skips that constructor call for the overworld, and when its
 * skip nests <em>outside</em> ModernFix's wrap the path is never set.</p>
 *
 * <p>{@link #prime} records the path exactly as ModernFix's own wrap would, through its
 * {@code IChunkGenerator} duck interface, so the later {@code StrongholdRingGate} start takes
 * ModernFix's normal cache branch. It is idempotent, so it is harmless when ModernFix's wrap already
 * ran. Without ModernFix (or with a build whose duck interface has moved) it is a no-op.</p>
 */
public final class ModernFixStrongholdCache {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String DUCK_CLASS = "org.embeddedt.modernfix.duck.IChunkGenerator";
    static final String SETTER_NAME = "mfix$setStrongholdCachePath";

    /** Resolved once; {@code null} when ModernFix is absent or its duck interface no longer links. */
    private static volatile Optional<Method> setter;
    private static volatile boolean warned;

    private ModernFixStrongholdCache() {}

    /**
     * Record the overworld's dimension folder + server on {@code state} the way ModernFix's constructor
     * wrap does. Returns true when the setter was invoked.
     */
    public static boolean prime(ChunkGeneratorStructureState state,
                                LevelStorageSource.LevelStorageAccess storage,
                                ResourceKey<Level> dimension,
                                MinecraftServer server) {
        Optional<Method> found = resolveSetter();
        if (found.isEmpty() || state == null || storage == null || dimension == null || server == null) {
            return false;
        }
        try {
            return callSetter(found.get(), state, storage.getDimensionPath(dimension), server);
        } catch (Throwable t) {
            warnOnce("could not prime ModernFix's stronghold cache path", t);
            return false;
        }
    }

    /** ModernFix's duck setter, looked up once through the class loader that sees every mod. */
    static Optional<Method> resolveSetter() {
        Optional<Method> cached = setter;
        if (cached != null) return cached;
        Optional<Method> resolved;
        try {
            Class<?> duck = Class.forName(DUCK_CLASS, false, ModernFixStrongholdCache.class.getClassLoader());
            resolved = findSetter(duck);
        } catch (ClassNotFoundException absent) {
            resolved = Optional.empty();
        } catch (Throwable t) {
            warnOnce("could not link ModernFix's stronghold cache duck interface", t);
            resolved = Optional.empty();
        }
        setter = resolved;
        return resolved;
    }

    /** Pure: the {@code mfix$setStrongholdCachePath(Path, MinecraftServer)} method of a duck type, if declared. */
    static Optional<Method> findSetter(Class<?> duck) {
        try {
            return Optional.of(duck.getMethod(SETTER_NAME, Path.class, MinecraftServer.class));
        } catch (NoSuchMethodException e) {
            return Optional.empty();
        }
    }

    /** Pure: invoke the setter on {@code state} if it implements the setter's declaring type. */
    static boolean callSetter(Method setter, Object state, Path path, Object server) throws Exception {
        if (!setter.getDeclaringClass().isInstance(state)) return false;
        setter.invoke(state, path, server);
        return true;
    }

    private static void warnOnce(String what, Throwable t) {
        if (warned) return;
        warned = true;
        LOGGER.warn("[DungeonTrain] {} — stronghold rings fall back to vanilla timing: {}", what, t.toString());
    }
}
