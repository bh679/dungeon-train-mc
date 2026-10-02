package games.brennan.dungeontrain.world;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tells a Dungeon Train save apart from any other world <em>before it opens</em>, from the save
 * folder alone. For code that runs ahead of world load (BCLib's patch prompt) and so has no
 * {@link DungeonTrainWorldData} instance, registries or server to ask.
 *
 * <p>Three signals, in order:</p>
 * <ol>
 *   <li><b>The overworld's stored dimension.</b> Every DT world preset but one gives
 *       {@code minecraft:overworld} a {@code dungeontrain:} dimension type
 *       ({@code dungeontrain:overworld}, {@code dungeontrain:overworld_y80},
 *       {@code dungeontrain:builder}, …) and/or {@code dungeontrain:} noise settings, written
 *       verbatim under {@code Data.WorldGenSettings.dimensions."minecraft:overworld"} in
 *       {@code level.dat}.</li>
 *   <li><b>The preset marker.</b> {@code dungeontrain:dungeon_train_compat} (Compatible Terrain)
 *       deliberately uses the vanilla overworld type and noise, and WorldWeaver rewrites every
 *       world's Nether and End, so its {@code level.dat} is the same as a plain world's. What
 *       separates them is {@value #PRESET_MARKER_TAG} in {@code data/dungeontrain_world.dat},
 *       recorded once at world creation: true for a {@code dungeontrain:} preset, false for any
 *       other. The file's mere presence says nothing — DT writes it into every world it opens, and
 *       runs a train there too.</li>
 *   <li><b>A guess, for saves older than the marker.</b> Nothing in such a save records its preset.
 *       A vanilla-overworld save DT runs a train in counts as a DT world only while this client has
 *       Compatible Terrain switched on — the one setting under which DT makes such worlds. That
 *       also takes in a plain world such a player made by hand before the marker existed; it never
 *       reaches a world created since, which carries a marker either way.</li>
 * </ol>
 *
 * <p>Callers treat "not DT" as "leave the library's behaviour alone", so a miss only costs the
 * world the library's own prompt.</p>
 */
public final class DungeonTrainSave {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Tag in {@code dungeontrain_world.dat}: whether the world was created from a {@code dungeontrain:} preset. */
    public static final String PRESET_MARKER_TAG = "dungeonTrainPreset";

    private static final String DT_PREFIX = "dungeontrain:";
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String WORLD_DATA_FILE = DungeonTrainWorldData.NAME + ".dat";
    /** Where {@code SavedData} keeps its payload inside the file. */
    private static final String SAVED_DATA_ROOT = "data";

    private DungeonTrainSave() {}

    /**
     * Whether the world in {@code levelDir} is a Dungeon Train world. Reads {@code level.dat}
     * (then {@code level.dat_old}) and {@code data/dungeontrain_world.dat}; a save whose
     * {@code level.dat} can't be read counts as non-DT (logged), since every caller's fallback is
     * the library's own behaviour.
     *
     * @param compatibleTerrainDefault this client's {@code defaultCompatibleTerrain} setting, which
     *                                 gates the guess for saves older than the preset marker
     */
    public static boolean isDungeonTrainSave(Path levelDir, boolean compatibleTerrainDefault) {
        CompoundTag levelDat = readLevelDat(levelDir);
        if (levelDat == null) return false;
        return isDungeonTrainSave(levelDat, readWorldData(levelDir), compatibleTerrainDefault);
    }

    /**
     * The same decision over already-parsed files. {@code worldData} is the root of
     * {@code dungeontrain_world.dat}, or {@code null} when the save has none (or it is unreadable).
     */
    public static boolean isDungeonTrainSave(CompoundTag levelDat, @Nullable CompoundTag worldData,
                                             boolean compatibleTerrainDefault) {
        if (isDungeonTrainLevelData(levelDat)) return true;
        if (worldData == null || !worldData.contains(SAVED_DATA_ROOT, Tag.TAG_COMPOUND)) return false;
        CompoundTag data = worldData.getCompound(SAVED_DATA_ROOT);
        if (data.contains(PRESET_MARKER_TAG)) return data.getBoolean(PRESET_MARKER_TAG);
        return compatibleTerrainDefault && isVanillaOverworld(levelDat) && startsWithTrain(data);
    }

    /** Whether a parsed {@code level.dat} root stores a {@code dungeontrain:} overworld. Missing tags read as non-DT. */
    public static boolean isDungeonTrainLevelData(CompoundTag levelDat) {
        CompoundTag overworld = overworld(levelDat);
        return overworld.getString("type").startsWith(DT_PREFIX) || noiseSettings(overworld).startsWith(DT_PREFIX);
    }

    /** Exactly what the Compatible Terrain preset stores for the overworld. */
    private static boolean isVanillaOverworld(CompoundTag levelDat) {
        CompoundTag overworld = overworld(levelDat);
        return OVERWORLD.equals(overworld.getString("type")) && OVERWORLD.equals(noiseSettings(overworld));
    }

    /** Mirrors {@link DungeonTrainWorldData#load}: a save from before the tag existed starts with a train. */
    private static boolean startsWithTrain(CompoundTag data) {
        return !data.contains(DungeonTrainWorldData.TAG_STARTS_WITH_TRAIN)
                || data.getBoolean(DungeonTrainWorldData.TAG_STARTS_WITH_TRAIN);
    }

    private static CompoundTag overworld(CompoundTag levelDat) {
        return levelDat.getCompound("Data")
                .getCompound("WorldGenSettings")
                .getCompound("dimensions")
                .getCompound(OVERWORLD);
    }

    private static String noiseSettings(CompoundTag overworld) {
        return overworld.getCompound("generator").getString("settings");
    }

    @Nullable
    private static CompoundTag readLevelDat(Path levelDir) {
        for (String name : new String[] {"level.dat", "level.dat_old"}) {
            CompoundTag tag = readCompressed(levelDir.resolve(name));
            if (tag != null) return tag;
        }
        return null;
    }

    @Nullable
    private static CompoundTag readWorldData(Path levelDir) {
        return readCompressed(levelDir.resolve("data").resolve(WORLD_DATA_FILE));
    }

    @Nullable
    private static CompoundTag readCompressed(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DT] Could not read {} to tell whether it is a Dungeon Train world", file, e);
            return null;
        }
    }
}
