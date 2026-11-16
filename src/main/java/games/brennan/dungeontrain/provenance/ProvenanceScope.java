package games.brennan.dungeontrain.provenance;

import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * The train carriage whose stamp is running on this thread, so items rolled during it can be marked
 * with their carriage ({@link ItemProvenance#markFresh}). The roller's own index argument can't serve:
 * tunnels and dimensional carriages pass world-X tiles and cell numbers in the same slot.
 */
public final class ProvenanceScope {

    private static final ThreadLocal<Integer> CURRENT = new ThreadLocal<>();

    private ProvenanceScope() {}

    /** The carriage index in scope, or empty outside a train carriage's stamp. */
    public static OptionalInt current() {
        Integer pIdx = CURRENT.get();
        return pIdx == null ? OptionalInt.empty() : OptionalInt.of(pIdx);
    }

    /** Run {@code body} with carriage {@code pIdx} in scope; nested scopes restore the outer one. */
    public static <T> T with(int pIdx, Supplier<T> body) {
        Integer prev = CURRENT.get();
        CURRENT.set(pIdx);
        try {
            return body.get();
        } finally {
            if (prev == null) CURRENT.remove(); else CURRENT.set(prev);
        }
    }

    /** {@link #with(int, Supplier)} for a body with no result. */
    public static void run(int pIdx, Runnable body) {
        with(pIdx, () -> {
            body.run();
            return null;
        });
    }
}
