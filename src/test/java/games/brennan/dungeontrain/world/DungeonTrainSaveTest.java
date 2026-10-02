package games.brennan.dungeontrain.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how {@link DungeonTrainSave} reads a save's {@code level.dat}: DT presets are recognised by the
 * {@code dungeontrain:} overworld dimension type or noise settings, anything else (and anything
 * unreadable) counts as a non-DT world so the BetterX library keeps its own behaviour there.
 */
class DungeonTrainSaveTest {

    @TempDir
    Path saveDir;

    @Test
    void dtNoisePresetIsDungeonTrain() {
        assertTrue(DungeonTrainSave.isDungeonTrainLevelData(levelDat("dungeontrain:overworld", "dungeontrain:overworld")));
    }

    @Test
    void floorVariantIsDungeonTrain() {
        assertTrue(DungeonTrainSave.isDungeonTrainLevelData(levelDat("dungeontrain:overworld_y80", "dungeontrain:overworld_y80")));
    }

    @Test
    void flatBuilderPresetIsDungeonTrainByDimensionTypeAlone() {
        CompoundTag root = levelDat("dungeontrain:builder", null);
        overworld(root).getCompound("generator").put("settings", new CompoundTag());
        assertTrue(DungeonTrainSave.isDungeonTrainLevelData(root));
    }

    /** Vanilla, {@code wover:normal} and Compatible Terrain (the documented blind spot) all store this. */
    @Test
    void vanillaOverworldIsNotDungeonTrain() {
        assertFalse(DungeonTrainSave.isDungeonTrainLevelData(levelDat("minecraft:overworld", "minecraft:overworld")));
    }

    @Test
    void missingWorldGenSettingsIsNotDungeonTrain() {
        CompoundTag root = new CompoundTag();
        root.put("Data", new CompoundTag());
        assertFalse(DungeonTrainSave.isDungeonTrainLevelData(root));
        assertFalse(DungeonTrainSave.isDungeonTrainLevelData(new CompoundTag()));
    }

    @Test
    void readsGzippedLevelDatFromSaveFolder() throws IOException {
        NbtIo.writeCompressed(levelDat("dungeontrain:overworld", "dungeontrain:overworld"), saveDir.resolve("level.dat"));
        assertTrue(DungeonTrainSave.isDungeonTrainSave(saveDir));
    }

    @Test
    void fallsBackToLevelDatOld() throws IOException {
        NbtIo.writeCompressed(levelDat("dungeontrain:overworld", "dungeontrain:overworld"), saveDir.resolve("level.dat_old"));
        assertTrue(DungeonTrainSave.isDungeonTrainSave(saveDir));
    }

    @Test
    void missingOrCorruptLevelDatIsNotDungeonTrain() throws IOException {
        assertFalse(DungeonTrainSave.isDungeonTrainSave(saveDir));
        Files.writeString(saveDir.resolve("level.dat"), "not nbt");
        assertFalse(DungeonTrainSave.isDungeonTrainSave(saveDir));
    }

    private static CompoundTag levelDat(String dimensionType, String noiseSettings) {
        CompoundTag generator = new CompoundTag();
        generator.putString("type", "minecraft:noise");
        if (noiseSettings != null) generator.putString("settings", noiseSettings);
        CompoundTag overworld = new CompoundTag();
        overworld.putString("type", dimensionType);
        overworld.put("generator", generator);
        CompoundTag dimensions = new CompoundTag();
        dimensions.put("minecraft:overworld", overworld);
        CompoundTag worldGen = new CompoundTag();
        worldGen.put("dimensions", dimensions);
        CompoundTag data = new CompoundTag();
        data.put("WorldGenSettings", worldGen);
        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        return root;
    }

    private static CompoundTag overworld(CompoundTag root) {
        return root.getCompound("Data").getCompound("WorldGenSettings")
                .getCompound("dimensions").getCompound("minecraft:overworld");
    }
}
