package games.brennan.dungeontrain.util;

import java.lang.management.ManagementFactory;

/**
 * The three numbers that describe how much machine is available, read straight from the JVM.
 *
 * <p>Deliberately free of Minecraft and NeoForge types. The numbers are wanted on <em>both</em>
 * sides: {@link games.brennan.dungeontrain.client.SystemSpecCollector} puts them in a bug report on
 * the client, and {@code CatchUpBurstAuto} picks a carriage-spawn pacing from them on the server —
 * including a dedicated server, where the specs that matter are the server box's, not any
 * player's.</p>
 *
 * <p>Every accessor is best-effort and never throws. {@link #physicalMemoryBytes()} returns
 * {@code 0} where it cannot be read at all; callers must treat {@code 0} as <em>unknown</em> rather
 * than as <em>none</em>, because those two mean opposite things when choosing a default.</p>
 */
public final class MachineSpecs {

    private MachineSpecs() {}

    /** Logical CPU cores visible to this JVM — hyperthreads counted, container limits respected. */
    public static int cores() {
        try {
            return Runtime.getRuntime().availableProcessors();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * JVM max heap ({@code -Xmx}) in bytes. This, not physical RAM, is what the game actually has:
     * CurseForge and Modrinth both set it explicitly at launch, so a 32 GB machine handed 2 GB is a
     * 2 GB machine as far as the server tick is concerned.
     */
    public static long maxHeapBytes() {
        try {
            return Runtime.getRuntime().maxMemory();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * Total physical RAM in bytes, or {@code 0} when it cannot be determined.
     *
     * <p>Read reflectively off the {@code com.sun} OS bean so a non-HotSpot JVM degrades to
     * {@code 0} instead of failing to link.</p>
     */
    public static long physicalMemoryBytes() {
        try {
            Object os = ManagementFactory.getOperatingSystemMXBean();
            Class<?> sun = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (!sun.isInstance(os)) {
                return 0L;
            }
            Object v = sun.getMethod("getTotalMemorySize").invoke(os);
            return v instanceof Long l ? l : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }
}
