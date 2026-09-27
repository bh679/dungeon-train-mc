package games.brennan.dungeontrain.compat;

import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reflective seam of {@link ModernFixStrongholdCache}, against a stand-in for ModernFix's duck interface. */
class ModernFixStrongholdCacheTest {

    /** Same shape as {@code org.embeddedt.modernfix.duck.IChunkGenerator}. */
    interface DuckStandIn {
        void mfix$setStrongholdCachePath(Path path, MinecraftServer server);
    }

    static final class StateStandIn implements DuckStandIn {
        Path path;
        MinecraftServer server;
        int calls;

        @Override
        public void mfix$setStrongholdCachePath(Path path, MinecraftServer server) {
            this.path = path;
            this.server = server;
            this.calls++;
        }
    }

    @Test
    void findsTheSetterOnTheDuckType() {
        Optional<Method> setter = ModernFixStrongholdCache.findSetter(DuckStandIn.class);
        assertTrue(setter.isPresent());
        assertEquals(ModernFixStrongholdCache.SETTER_NAME, setter.get().getName());
    }

    @Test
    void noSetterOnAnUnrelatedType() {
        assertTrue(ModernFixStrongholdCache.findSetter(Runnable.class).isEmpty());
    }

    @Test
    void callSetterRecordsPathAndServerOnAnImplementingState() throws Exception {
        Method setter = ModernFixStrongholdCache.findSetter(DuckStandIn.class).orElseThrow();
        StateStandIn state = new StateStandIn();
        Path path = Path.of("saves", "World 1");

        assertTrue(ModernFixStrongholdCache.callSetter(setter, state, path, null));

        assertEquals(path, state.path);
        assertNull(state.server);
        assertEquals(1, state.calls);
    }

    @Test
    void callSetterIsIdempotent() throws Exception {
        Method setter = ModernFixStrongholdCache.findSetter(DuckStandIn.class).orElseThrow();
        StateStandIn state = new StateStandIn();
        Path path = Path.of("saves", "World 1");

        ModernFixStrongholdCache.callSetter(setter, state, path, null);
        ModernFixStrongholdCache.callSetter(setter, state, path, null);

        assertEquals(path, state.path);
        assertEquals(2, state.calls);
    }

    @Test
    void callSetterSkipsAStateThatDoesNotImplementTheDuck() throws Exception {
        Method setter = ModernFixStrongholdCache.findSetter(DuckStandIn.class).orElseThrow();
        assertFalse(ModernFixStrongholdCache.callSetter(setter, new Object(), Path.of("x"), null));
    }

    @Test
    void withoutModernFixTheResolverIsEmptyAndPrimeIsANoOp() {
        // The test classpath has no ModernFix, so the real duck class is absent.
        assertTrue(ModernFixStrongholdCache.resolveSetter().isEmpty());
        assertFalse(ModernFixStrongholdCache.prime(null, null, null, null));
    }
}
