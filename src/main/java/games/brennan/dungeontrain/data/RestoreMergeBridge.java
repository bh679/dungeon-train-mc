package games.brennan.dungeontrain.data;

import com.mojang.logging.LogUtils;
import games.brennan.dungeonbackup.api.Registration;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;

/**
 * Registers {@link RestoreMergers} with Dungeon Backup's {@code mergeOnRestore} — when the
 * installed library has it.
 *
 * <p>{@code mergeOnRestore} arrived in Dungeon Backup 0.3.0. DT keeps compiling against, and
 * requiring, the build that is live on CurseForge and Modrinth; this bridge is what lets the fix
 * switch itself on the moment a newer library is installed (a CurseForge top-level copy wins over
 * the jarJar'd one) without raising the floor first. Against an older library it registers nothing
 * and restores stay skip-if-exists, exactly as before.</p>
 *
 * <p>Reflection is confined to here, behind {@code catch (ReflectiveOperationException |
 * RuntimeException | LinkageError)} — mirroring {@code compat.EnderChestResetBridge}. Once the floor
 * is raised to 0.3.0 this class can be replaced by direct calls.</p>
 */
public final class RestoreMergeBridge {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MERGER_TYPE = "games.brennan.dungeonbackup.api.RestoreMerger";

    private RestoreMergeBridge() {}

    /** Does the installed Dungeon Backup accept restore mergers? */
    public static boolean available() {
        return mergeMethod().isPresent();
    }

    /**
     * Register every merger in {@code mergers} under {@code label}.
     *
     * @return how many were registered — 0 against a library without {@code mergeOnRestore}
     */
    public static int registerAll(Registration.Builder builder, String label,
                                  Map<String, RestoreMergers.Merger> mergers) {
        Optional<Method> method = mergeMethod();
        if (method.isEmpty()) {
            LOGGER.info("[DungeonTrain] Installed Dungeon Backup has no mergeOnRestore (needs 0.3.0+) — "
                + "restores keep skipping progress files that already exist");
            return 0;
        }
        Class<?> mergerType = method.get().getParameterTypes()[2];
        int registered = 0;
        for (var entry : mergers.entrySet()) {
            try {
                method.get().invoke(builder, label, entry.getKey(), proxy(mergerType, entry.getValue()));
                registered++;
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("[DungeonTrain] Couldn't register restore merger for {}: {}", entry.getKey(), e.toString());
            }
        }
        return registered;
    }

    private static Optional<Method> mergeMethod() {
        try {
            Class<?> mergerType = Class.forName(MERGER_TYPE, false, Registration.class.getClassLoader());
            return Optional.of(Registration.Builder.class.getMethod(
                "mergeOnRestore", String.class, String.class, mergerType));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** An instance of the library's {@code RestoreMerger} that delegates to {@code merger}. */
    private static Object proxy(Class<?> mergerType, RestoreMergers.Merger merger) {
        return Proxy.newProxyInstance(mergerType.getClassLoader(), new Class<?>[]{mergerType},
            (self, called, args) -> switch (called.getName()) {
                case "merge" -> merger.merge((byte[]) args[0], (byte[]) args[1]);
                case "toString" -> "DungeonTrain RestoreMerger";
                case "hashCode" -> System.identityHashCode(self);
                case "equals" -> self == args[0];
                default -> throw new UnsupportedOperationException(called.getName());
            });
    }
}
