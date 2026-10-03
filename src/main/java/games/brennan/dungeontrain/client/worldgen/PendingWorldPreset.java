package games.brennan.dungeontrain.client.worldgen;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

import javax.annotation.Nullable;

/**
 * Client-side static holder for whether the world about to be created comes from a
 * {@code dungeontrain:} world preset. Published by every path that picks a preset for a new world
 * (the Create World screen, the death-screen relaunch, the title-screen quick worlds), taken once by
 * {@code WorldLifecycleEvents} and written into {@code DungeonTrainWorldData} — the only durable
 * record of the preset, since {@code level.dat} keeps the dimensions a preset produced and not its
 * name, and Compatible Terrain produces the vanilla ones.
 *
 * <p>Unlike {@link PendingStartingDimension} there is no default: nothing published means the preset
 * is unknown, and an unknown preset is never recorded.</p>
 *
 * Client-only — never referenced from a class loaded on a dedicated server.
 */
public final class PendingWorldPreset {

    private static volatile Boolean dungeonTrainPreset;

    private PendingWorldPreset() {}

    /** Publish the preset the next new world will be created from; {@code null} is an unregistered one. */
    public static void set(@Nullable ResourceKey<WorldPreset> preset) {
        dungeonTrainPreset = preset != null && DungeonTrain.MOD_ID.equals(preset.location().getNamespace());
    }

    /** The published answer, or {@code null} when nothing was published; clears the holder. */
    @Nullable
    public static Boolean take() {
        Boolean taken = dungeonTrainPreset;
        dungeonTrainPreset = null;
        return taken;
    }
}
