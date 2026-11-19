package games.brennan.dungeontrain.client.perf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.perf.ClientStallWatchdog.LONG_STALL_MS;
import static games.brennan.dungeontrain.client.perf.ClientStallWatchdog.MAX_SHORT_DUMPS;
import static games.brennan.dungeontrain.client.perf.ClientStallWatchdog.NEVER;
import static games.brennan.dungeontrain.client.perf.ClientStallWatchdog.SHORT_DUMP_COOLDOWN_MS;
import static games.brennan.dungeontrain.client.perf.ClientStallWatchdog.STALL_LOG_MS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage of {@link ClientStallWatchdog}'s two decisions — whether a freeze may be dumped
 * now, and whether the render thread is parked in the OS event pump — in the bootstrap-free style of
 * {@code ServerStallWatchdogTest}. Stack fixtures carry only the class and method names the rule reads.
 */
final class ClientStallWatchdogTest {

    private static final long NOW = 1_000_000L;

    private static StackTraceElement frame(String className, String methodName) {
        return new StackTraceElement(className, methodName, "Source.java", 1);
    }

    @Test
    @DisplayName("Nothing under the threshold is dumped, even with the budget untouched")
    void underThresholdNeverDumps() {
        assertFalse(ClientStallWatchdog.mayDump(STALL_LOG_MS - 1, NEVER, 0, NOW));
    }

    @Test
    @DisplayName("The first short freeze is dumped")
    void firstShortFreezeDumps() {
        assertTrue(ClientStallWatchdog.mayDump(STALL_LOG_MS, NEVER, 0, NOW));
    }

    @Test
    @DisplayName("A second short freeze inside the cooldown is held back; after it, dumped")
    void shortFreezesShareACooldown() {
        long last = NOW - SHORT_DUMP_COOLDOWN_MS + 1;
        assertFalse(ClientStallWatchdog.mayDump(STALL_LOG_MS, last, 1, NOW));
        assertTrue(ClientStallWatchdog.mayDump(STALL_LOG_MS, NOW - SHORT_DUMP_COOLDOWN_MS, 1, NOW));
    }

    @Test
    @DisplayName("Short freezes stop once the session budget is spent")
    void shortBudgetIsCapped() {
        assertFalse(ClientStallWatchdog.mayDump(STALL_LOG_MS, NEVER, MAX_SHORT_DUMPS, NOW));
    }

    @Test
    @DisplayName("A long freeze is always dumped, cooldown and budget notwithstanding")
    void longFreezeAlwaysDumps() {
        assertTrue(ClientStallWatchdog.mayDump(LONG_STALL_MS, NOW, MAX_SHORT_DUMPS, NOW));
    }

    @Test
    @DisplayName("A render thread parked in glfwPollEvents (macOS window drag) is labelled OS event loop")
    void pollEventsIsOsEventLoop() {
        StackTraceElement[] stack = {
            frame("org.lwjgl.glfw.GLFW", "nglfwPollEvents"),
            frame("org.lwjgl.glfw.GLFW", "glfwPollEvents"),
            frame("com.mojang.blaze3d.systems.RenderSystem", "pollEvents"),
            frame("com.mojang.blaze3d.systems.RenderSystem", "flipFrame"),
            frame("com.mojang.blaze3d.platform.Window", "updateDisplay"),
            frame("net.minecraft.client.Minecraft", "runTick"),
        };
        assertTrue(ClientStallWatchdog.isOsEventLoop(stack));
    }

    @Test
    @DisplayName("A freeze in game code is not the OS event loop, even with the pump further down")
    void gameCodeIsNotOsEventLoop() {
        StackTraceElement[] stack = {
            frame("java.lang.Thread", "sleep"),
            frame("net.minecraft.client.sounds.SoundEngine", "play"),
            frame("net.minecraft.client.sounds.SoundManager", "play"),
            frame("net.minecraft.client.multiplayer.ClientLevel", "playSound"),
            frame("net.minecraft.client.multiplayer.ClientPacketListener", "handleSoundEvent"),
            frame("net.minecraft.client.Minecraft", "runTick"),
            frame("org.lwjgl.glfw.GLFW", "glfwPollEvents"),
        };
        assertFalse(ClientStallWatchdog.isOsEventLoop(stack));
    }

    @Test
    @DisplayName("Null and empty stacks are not the OS event loop")
    void emptyStacks() {
        assertFalse(ClientStallWatchdog.isOsEventLoop(null));
        assertFalse(ClientStallWatchdog.isOsEventLoop(new StackTraceElement[0]));
    }
}
