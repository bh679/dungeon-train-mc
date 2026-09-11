package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ServerStallWatchdog#isIdleBetweenTicks} — the test that decides whether a
 * stopped tick counter is a freeze worth dumping or a player in the pause menu.
 *
 * <p>Pure-logic coverage with no Minecraft types, in the bootstrap-free style of
 * {@link NearestPickerTest}: the subject is a {@code StackTraceElement[]}, so the fixtures are the
 * <b>real</b> stacks the rule was drawn around rather than invented ones — the idle stack from the
 * player log of 09 Sep 2026 (which produced 64 false stall warnings in an hour) and the fluid-cascade
 * chunk load from issue #1335, which is a genuine stall and must keep being reported.</p>
 *
 * <p>Frames carry only the class and method names because that is all the rule reads. Note the
 * {@code TRANSFORMER/minecraft@1.21.1/} prefix seen in a log line is the module banner
 * {@code StackTraceElement.toString()} prints, not part of {@code getClassName()} — the fixtures use
 * the plain binary names the rule is matched against.</p>
 */
final class ServerStallWatchdogTest {

    private static final String SERVER = "net.minecraft.server.MinecraftServer";

    /** A frame with just the two fields the rule reads. */
    private static StackTraceElement frame(String className, String methodName) {
        return new StackTraceElement(className, methodName, "Source.java", 1);
    }

    /**
     * The 194-second "stall" from the player log, verbatim and in order: an integrated server parked
     * in the pause menu with ModernFix's wrappers interleaved.
     */
    private static StackTraceElement[] pausedStack() {
        return new StackTraceElement[] {
            frame("jdk.internal.misc.Unsafe", "park"),
            frame("java.util.concurrent.locks.LockSupport", "parkNanos"),
            frame(SERVER, "wrapOperation$zpa000$modernfix$waitLongerForTasks"),
            frame(SERVER, "waitForTasks"),
            frame("net.minecraft.util.thread.BlockableEventLoop", "managedBlock"),
            frame(SERVER, "managedBlock"),
            frame(SERVER, "mixinextras$bridge$managedBlock$271"),
            frame(SERVER, "wrapOperation$zpa000$modernfix$managedBlock"),
            frame(SERVER, "waitUntilNextTick"),
            frame(SERVER, "runServer"),
            frame(SERVER, "lambda$spin$2"),
            frame("java.lang.Thread", "run"),
        };
    }

    /**
     * The stall recorded in issue #1335: a fluid spread on the server thread dragging in a
     * synchronous chunk load through Sable's physics pipeline. Parks, and goes through
     * {@code managedBlock} — the two things a looser rule would have keyed on — but never through
     * the tick loop's wait, because the work is inside {@code tickServer}.
     */
    private static StackTraceElement[] chunkLoadStallStack() {
        return new StackTraceElement[] {
            frame("jdk.internal.misc.Unsafe", "park"),
            frame("java.util.concurrent.locks.LockSupport", "parkNanos"),
            frame("net.minecraft.util.thread.BlockableEventLoop", "managedBlock"),
            frame("net.minecraft.server.level.ServerChunkCache", "getChunk"),
            frame("net.minecraft.world.level.Level", "getChunk"),
            frame("dev.ryanhcode.sable.physics.LevelAccelerator", "grabChunkFast"),
            frame("dev.ryanhcode.sable.physics.LevelAccelerator", "getBlockState"),
            frame("dev.ryanhcode.sable.physics.VoxelNeighborhoodState", "getState"),
            frame("dev.ryanhcode.sable.physics.RapierPhysicsPipeline", "handleBlockChange"),
            frame("dev.ryanhcode.sable.SableCommonEvents", "handleBlockChange"),
            frame("net.minecraft.world.level.Level", "setBlock"),
            frame("net.minecraft.world.level.material.FlowingFluid", "spread"),
            frame("net.minecraft.server.level.ServerLevel", "tickFluid"),
            frame("net.minecraft.server.level.ServerLevel", "tick"),
            frame(SERVER, "tickChildren"),
            frame(SERVER, "tickServer"),
            frame(SERVER, "runServer"),
            frame("java.lang.Thread", "run"),
        };
    }

    @Test
    @DisplayName("the pause-menu stack from the player log reads as idle, not a stall")
    void pausedStack_isIdle() {
        assertTrue(ServerStallWatchdog.isIdleBetweenTicks(pausedStack()));
    }

    @Test
    @DisplayName("the #1335 chunk-load stall is still a stall — it parks and managed-blocks too")
    void chunkLoadStall_isNotIdle() {
        assertFalse(ServerStallWatchdog.isIdleBetweenTicks(chunkLoadStallStack()));
    }

    @Test
    @DisplayName("a mixin that swallows waitForTasks still leaves waitUntilNextTick to match")
    void idleSurvivesAMissingFrame() {
        List<StackTraceElement> frames = new ArrayList<>(List.of(pausedStack()));
        frames.removeIf(f -> f.getMethodName().contains("ForTasks"));
        assertTrue(ServerStallWatchdog.isIdleBetweenTicks(
            frames.toArray(new StackTraceElement[0])));
    }

    @Test
    @DisplayName("a wait frame buried under real work is not idle — the depth cap holds")
    void waitFramePastTheDepthCap_isNotIdle() {
        List<StackTraceElement> frames = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            frames.add(frame("games.brennan.dungeontrain.train.TrainCarriageAppender", "tick" + i));
        }
        frames.add(frame(SERVER, "waitUntilNextTick"));
        frames.add(frame("java.lang.Thread", "run"));
        assertFalse(ServerStallWatchdog.isIdleBetweenTicks(
            frames.toArray(new StackTraceElement[0])));
    }

    @Test
    @DisplayName("a null or empty stack is not idle — nothing to prove it")
    void nullAndEmptyAreNotIdle() {
        assertFalse(ServerStallWatchdog.isIdleBetweenTicks(null));
        assertFalse(ServerStallWatchdog.isIdleBetweenTicks(new StackTraceElement[0]));
    }
}
