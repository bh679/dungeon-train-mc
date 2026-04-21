package games.brennan.dungeontrain.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Server-scoped Forge config for Dungeon Train tunables.
 *
 * Stored per-world at {@code <save>/serverconfig/dungeontrain-server.toml}.
 * Registered from {@link games.brennan.dungeontrain.DungeonTrain} constructor.
 *
 * Exposes two values:
 *   - {@code numCarriages} — rolling-window size, [1, 50]
 *   - {@code speed} — train speed along +X in blocks/second, [0.0, 20.0]
 */
public final class DungeonTrainConfig {

    public static final int MIN_CARRIAGES = 1;
    public static final int MAX_CARRIAGES = 50;
    public static final int DEFAULT_CARRIAGES = 5;

    public static final double MIN_SPEED = 0.0;
    public static final double MAX_SPEED = 20.0;
    public static final double DEFAULT_SPEED = 2.0;

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue NUM_CARRIAGES;
    public static final ForgeConfigSpec.DoubleValue SPEED;

    static {
        Pair<Holder, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder()
                .configure(DungeonTrainConfig::build);
        SPEC = pair.getRight();
        NUM_CARRIAGES = pair.getLeft().numCarriages;
        SPEED = pair.getLeft().speed;
    }

    private DungeonTrainConfig() {}

    private static Holder build(ForgeConfigSpec.Builder b) {
        b.push("train");
        ForgeConfigSpec.IntValue numCarriages = b
                .comment("Number of carriages visible in the rolling window around each player.")
                .defineInRange("numCarriages", DEFAULT_CARRIAGES, MIN_CARRIAGES, MAX_CARRIAGES);
        ForgeConfigSpec.DoubleValue speed = b
                .comment("Train speed along +X in blocks per second.")
                .defineInRange("speed", DEFAULT_SPEED, MIN_SPEED, MAX_SPEED);
        b.pop();
        return new Holder(numCarriages, speed);
    }

    /**
     * SERVER config is loaded per-world, so outside an active world (e.g. the
     * title-screen Mods → Config menu) these values aren't available yet.
     * Call this before reading or writing to avoid IllegalStateException.
     */
    public static boolean isLoaded() {
        return SPEC.isLoaded();
    }

    public static int getNumCarriages() {
        return isLoaded() ? NUM_CARRIAGES.get() : DEFAULT_CARRIAGES;
    }

    public static double getSpeed() {
        return isLoaded() ? SPEED.get() : DEFAULT_SPEED;
    }

    public static void setNumCarriages(int value) {
        if (!isLoaded()) return;
        int clamped = Math.max(MIN_CARRIAGES, Math.min(MAX_CARRIAGES, value));
        NUM_CARRIAGES.set(clamped);
        NUM_CARRIAGES.save();
    }

    public static void setSpeed(double value) {
        if (!isLoaded()) return;
        double clamped = Math.max(MIN_SPEED, Math.min(MAX_SPEED, value));
        SPEED.set(clamped);
        SPEED.save();
    }

    private record Holder(
            ForgeConfigSpec.IntValue numCarriages,
            ForgeConfigSpec.DoubleValue speed
    ) {}
}
