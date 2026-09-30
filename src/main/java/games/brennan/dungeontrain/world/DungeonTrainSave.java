package games.brennan.dungeontrain.world;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tells a Dungeon Train save apart from any other world <em>before it opens</em>, from its
 * {@code level.dat} alone. For code that runs ahead of world load (BCLib's patch prompt) and so has
 * no {@link DungeonTrainWorldData} or server to ask. That data file can't be the signal anyway: DT
 * writes {@code data/dungeontrain_world.dat} into every world it runs in.
 *
 * <p>The signal is the overworld's stored dimension: every DT world preset gives
 * {@code minecraft:overworld} a {@code dungeontrain:} dimension type ({@code dungeontrain:overworld},
 * {@code dungeontrain:overworld_y80}, {@code dungeontrain:builder}, …) and/or {@code dungeontrain:}
 * noise settings, and both are written verbatim under
 * {@code Data.WorldGenSettings.dimensions."minecraft:overworld"}.</p>
 *
 * <p>Known blind spot: {@code dungeontrain:dungeon_train_compat} (Compatible Terrain) deliberately
 * uses the vanilla overworld type and noise, so its saves read as non-DT here. Callers treat
 * "not DT" as "leave the library's behaviour alone", so the miss only costs such a world the
 * library's own prompt.</p>
 */
public final class DungeonTrainSave {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String DT_PREFIX = "dungeontrain:";
    private static final String OVERWORLD = "minecraft:overworld";

    private DungeonTrainSave() {}

    /**
     * Whether the world in {@code levelDir} was created from a Dungeon Train preset. Reads
     * {@code level.dat}, then {@code level.dat_old}; a save neither can be read from counts as
     * non-DT (logged), since every caller's fallback is the library's own behaviour.
     */
    public static boolean isDungeonTrainSave(Path levelDir) {
        for (String name : new String[] {"level.dat", "level.dat_old"}) {
            Path file = levelDir.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            try {
                return isDungeonTrainLevelData(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[DT] Could not read {} to tell whether it is a Dungeon Train world", file, e);
            }
        }
        return false;
    }

    /** Whether a parsed {@code level.dat} root describes a Dungeon Train world. Missing tags read as non-DT. */
    public static boolean isDungeonTrainLevelData(CompoundTag levelDat) {
        CompoundTag overworld = levelDat.getCompound("Data")
                .getCompound("WorldGenSettings")
                .getCompound("dimensions")
                .getCompound(OVERWORLD);
        String dimensionType = overworld.getString("type");
        String noiseSettings = overworld.getCompound("generator").getString("settings");
        return dimensionType.startsWith(DT_PREFIX) || noiseSettings.startsWith(DT_PREFIX);
    }
}
