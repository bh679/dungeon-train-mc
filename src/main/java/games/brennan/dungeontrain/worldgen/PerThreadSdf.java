package games.brennan.dungeontrain.worldgen;

import org.betterx.bclib.sdf.SDF;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A per-thread copy of a shared {@code static} BCLib SDF graph, for third-party features that re-configure
 * such a graph on every placement (see {@code mixin/betterend/BetterEnd*PerThreadMixin}). BetterEnd builds
 * its shapes once in a static initialiser, then {@code place()} sets radii, blocks and offsets on the shared
 * nodes before filling; with DT decorating BetterEnd chunks on every worldgen worker, two placements at once
 * fill with each other's settings. Each thread gets its own copy of the whole graph instead.
 *
 * <p>The copy keeps the graph's wiring: every SDF node and scratch vector reachable from {@code shared} is
 * cloned once per thread, so two statics that point into the same graph still do in the copy. Everything
 * else — functions, block states, noise — is immutable and stays shared.</p>
 */
public final class PerThreadSdf {

    /** Dev switch for the determinism A/B: {@code -Ddungeontrain.betterendPerThreadSdf=false} hands back the shared graph. */
    private static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("dungeontrain.betterendPerThreadSdf"));

    private static final ThreadLocal<Map<Object, Object>> COPIES = ThreadLocal.withInitial(IdentityHashMap::new);

    private PerThreadSdf() {}

    /** This thread's copy of {@code shared} and of everything it reaches, made on first use. */
    public static <T> T of(T shared) {
        if (!ENABLED) return shared;
        Map<Object, Object> copies = COPIES.get();
        try {
            return copyOf(shared, copies);
        } catch (RuntimeException e) {
            // A copy that failed part-way is in the map half-built; never hand it to a later placement.
            copies.clear();
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T copyOf(T shared, Map<Object, Object> copies) {
        return (T) copy(shared, copies);
    }

    private static Object copy(Object original, Map<Object, Object> copies) {
        if (original == null) return null;
        Object known = copies.get(original);
        if (known != null) return known;
        if (original instanceof Vector3f vector) {
            Vector3f copy = new Vector3f(vector);
            copies.put(original, copy);
            return copy;
        }
        if (original instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            copies.put(original, copy);
            for (Object element : list) copy.add(copy(element, copies));
            return copy;
        }
        // Only the nodes themselves: BCLib's own lambdas live in the same package and are shared as they are.
        if (!(original instanceof SDF)) return original;
        return copyNode(original, copies);
    }

    /**
     * True if the copy's constructor already made its own instance of the lambda the original holds. Such a
     * lambda captures its node ({@code SDFFlatWave} reads its own ray count and angle through one), so the
     * copy must keep the one bound to itself; the original's would go on reading the shared node.
     */
    private static boolean isOwnLambda(Object own, Object shared) {
        return own != null && shared != null && own != shared
            && own.getClass() == shared.getClass() && own.getClass().isHidden();
    }

    private static Object copyNode(Object original, Map<Object, Object> copies) {
        try {
            var constructor = original.getClass().getDeclaredConstructor();
            constructor.setAccessible(true);
            Object copy = constructor.newInstance();
            copies.put(original, copy);
            for (Class<?> type = original.getClass(); type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    Object value = field.get(original);
                    if (field.getType().isPrimitive()) {
                        field.set(copy, value);
                    } else if (!isOwnLambda(field.get(copy), value)) {
                        field.set(copy, copy(value, copies));
                    }
                }
            }
            return copy;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Could not copy SDF node " + original.getClass().getName()
                + " for per-thread world generation", e);
        }
    }
}
