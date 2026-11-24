package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.compat.RagdollBridge.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A mod patching Sable: Ragdolls for another Ragdolls version (Sable: Ragdoll - Patch 1.9.1 on
 * Ragdolls 0.8.1) throws a mixin error, a plain {@link Error}, when the patched class loads. That
 * must switch death bodies off, never reach the server tick.
 */
class RagdollBridgeProbeTest {

    /** Stand-in for Mixin's {@code MixinTransformerError}: an {@link Error} that is not a {@link LinkageError}. */
    private static final class FakeMixinError extends Error {
        FakeMixinError() {
            super("An unexpected critical error was encountered");
        }
    }

    @Test
    void mixinFailureDisablesBodies() {
        assertEquals(Verdict.DISABLE, RagdollBridge.judge(new FakeMixinError()));
    }

    @Test
    void linkageFailureDisablesBodies() {
        assertEquals(Verdict.DISABLE, RagdollBridge.judge(new NoClassDefFoundError("dev/leo/x")));
        assertEquals(Verdict.DISABLE, RagdollBridge.judge(new NoSuchMethodError("unseatRider")));
    }

    @Test
    void renamedClassIsSkipped() {
        assertEquals(Verdict.SKIP, RagdollBridge.judge(new ClassNotFoundException("dev.leo.x")));
    }

    @Test
    void jvmTroubleIsRethrown() {
        assertEquals(Verdict.RETHROW, RagdollBridge.judge(new OutOfMemoryError()));
        assertEquals(Verdict.RETHROW, RagdollBridge.judge(new StackOverflowError()));
    }

    @Test
    void probesTheClassesInTheCrashStack() {
        assertFalse(RagdollBridge.PROBED_CLASSES.isEmpty());
        RagdollBridge.PROBED_CLASSES.forEach(name ->
            assertEquals(true, name.startsWith("dev.leo.sableplayerragdoll."), name));
    }
}
