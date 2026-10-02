package games.brennan.dungeontrain.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how {@link DungeonTrainSave} reads a save folder: DT presets are recognised by the
 * {@code dungeontrain:} overworld dimension type or noise settings in {@code level.dat}; Compatible
 * Terrain, which stores the vanilla ones, by the preset marker in {@code dungeontrain_world.dat};
 * a save older than the marker only while Compatible Terrain is this client's default. Anything
 * else (and anything unreadable) counts as a non-DT world so the BetterX library keeps its own
 * behaviour there.
 *
 * <p>{@code dungeontrain_world.dat} fixtures come out of {@link DungeonTrainWorldData#save} itself,
 * so they hold what a real world holds — including the marker being absent until recorded.</p>
 */
class DungeonTrainSaveTest {

    private static final boolean COMPAT_ON = true;
    private static final boolean COMPAT_OFF = false;

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

    /** Vanilla, {@code wover:normal} and Compatible Terrain all store this — {@code level.dat} alone can't tell them apart. */
    @Test
    void vanillaOverworldIsNotDungeonTrainByLevelDatAlone() {
        assertFalse(DungeonTrainSave.isDungeonTrainLevelData(vanillaLevelDat()));
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
        assertTrue(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_OFF));
    }

    @Test
    void fallsBackToLevelDatOld() throws IOException {
        NbtIo.writeCompressed(levelDat("dungeontrain:overworld", "dungeontrain:overworld"), saveDir.resolve("level.dat_old"));
        assertTrue(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_OFF));
    }

    @Test
    void missingOrCorruptLevelDatIsNotDungeonTrain() throws IOException {
        assertFalse(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_ON));
        Files.writeString(saveDir.resolve("level.dat"), "not nbt");
        assertFalse(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_ON));
    }

    @Test
    @DisplayName("a dungeontrain: overworld needs no marker, and a marker can't talk it out of being DT")
    void dtOverworldWinsOverTheMarker() throws IOException {
        writeSave(levelDat("dungeontrain:overworld", "dungeontrain:overworld"), worldData(false, true));
        assertTrue(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_OFF));
    }

    @Test
    @DisplayName("a Compatible Terrain world is recognised by its creation marker, whatever the client's setting is now")
    void compatPresetWithMarkerIsDungeonTrain() throws IOException {
        writeSave(vanillaLevelDat(), worldData(true, true));
        assertTrue(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_OFF));
        assertTrue(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_ON));
    }

    @Test
    @DisplayName("the same world behind BetterX's rewritten Nether and End is still recognised")
    void compatPresetWithBetterXDimensionsIsDungeonTrain() throws IOException {
        writeSave(betterXLevelDat(), worldData(true, true));
        assertTrue(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_OFF));
    }

    @Test
    @DisplayName("a plain vanilla save DT has never opened is not a DT world")
    void plainVanillaSaveIsNotDungeonTrain() throws IOException {
        writeSave(vanillaLevelDat(), null);
        assertFalse(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_ON));
    }

    @Test
    @DisplayName("a plain world made with DT installed is stamped non-DT and is never guessed at")
    void freshNonDtWorldMadeWithDtInstalledIsNotDungeonTrain() throws IOException {
        writeSave(vanillaLevelDat(), worldData(false, true));
        assertFalse(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_ON));
        assertFalse(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_OFF));
    }

    @Test
    @DisplayName("a BetterNether/BetterEnd world the player made themselves is not a DT world")
    void betterXSaveIsNotDungeonTrain() throws IOException {
        writeSave(betterXLevelDat(), null);
        assertFalse(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_ON));
    }

    @Test
    @DisplayName("nor is one made with DT installed, which carries the non-DT stamp")
    void betterXSaveStampedNonDtIsNotDungeonTrain() throws IOException {
        writeSave(betterXLevelDat(), worldData(false, true));
        assertFalse(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_ON));
    }

    @Test
    @DisplayName("an old Compatible Terrain save, from before the marker, is recognised while Compatible Terrain is on")
    void oldCompatSaveWithoutMarkerIsDungeonTrainWhenCompatIsOn() throws IOException {
        writeSave(betterXLevelDat(), worldData(null, true));
        assertTrue(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_ON));
    }

    @Test
    @DisplayName("and falls through to the library's prompt when it is off — nothing in the save says which it is")
    void oldSaveWithoutMarkerIsNotDungeonTrainWhenCompatIsOff() throws IOException {
        writeSave(betterXLevelDat(), worldData(null, true));
        assertFalse(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_OFF));
    }

    @Test
    @DisplayName("an old save whose data file predates even startsWithTrain reads the way the world itself loads: train on")
    void oldSaveWithoutStartsWithTrainTagCountsAsTrainWorld() throws IOException {
        CompoundTag worldData = worldData(null, true);
        worldData.getCompound("data").remove("startsWithTrain");
        writeSave(vanillaLevelDat(), worldData);
        assertTrue(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_ON));
    }

    @Test
    @DisplayName("an old save with the train switched off is not guessed to be a DT world")
    void oldSaveWithoutTrainIsNotDungeonTrain() throws IOException {
        writeSave(vanillaLevelDat(), worldData(null, false));
        assertFalse(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_ON));
    }

    @Test
    @DisplayName("the guess only covers the overworld Compatible Terrain stores, not some other mod's")
    void oldSaveWithForeignOverworldIsNotDungeonTrain() throws IOException {
        writeSave(levelDat("othermod:overworld", "othermod:overworld"), worldData(null, true));
        assertFalse(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_ON));
    }

    @Test
    @DisplayName("a corrupt or payload-less data file is no evidence either way")
    void unreadableWorldDataIsNotDungeonTrain() throws IOException {
        writeSave(vanillaLevelDat(), null);
        Files.createDirectories(saveDir.resolve("data"));
        Files.writeString(saveDir.resolve("data").resolve("dungeontrain_world.dat"), "not nbt");
        assertFalse(DungeonTrainSave.isDungeonTrainSave(saveDir, COMPAT_ON));

        assertFalse(DungeonTrainSave.isDungeonTrainSave(vanillaLevelDat(), new CompoundTag(), COMPAT_ON));
    }

    @Test
    @DisplayName("a world's marker is unknown until recorded, and re-saving an old world keeps it unknown")
    void markerStaysAbsentUntilRecorded() {
        DungeonTrainWorldData legacy = DungeonTrainWorldData.createDefault();
        assertNull(legacy.dungeonTrainPreset());
        CompoundTag saved = legacy.save(new CompoundTag(), null);
        assertFalse(saved.contains(DungeonTrainSave.PRESET_MARKER_TAG));
        assertNull(DungeonTrainWorldData.load(saved).dungeonTrainPreset());
    }

    @Test
    @DisplayName("a recorded marker survives save and load, and the first answer stands")
    void recordedMarkerRoundTripsAndIsNotOverwritten() {
        for (boolean recorded : new boolean[] {true, false}) {
            DungeonTrainWorldData data = DungeonTrainWorldData.createDefault();
            data.recordDungeonTrainPreset(recorded);
            data.recordDungeonTrainPreset(!recorded);

            DungeonTrainWorldData loaded = DungeonTrainWorldData.load(data.save(new CompoundTag(), null));
            assertEquals(recorded, loaded.dungeonTrainPreset());
        }
    }

    private void writeSave(CompoundTag levelDat, CompoundTag worldData) throws IOException {
        NbtIo.writeCompressed(levelDat, saveDir.resolve("level.dat"));
        if (worldData == null) return;
        Files.createDirectories(saveDir.resolve("data"));
        NbtIo.writeCompressed(worldData, saveDir.resolve("data").resolve("dungeontrain_world.dat"));
    }

    /** {@code dungeontrain_world.dat} as {@code SavedData} writes it. {@code marker == null} is a world from before the marker. */
    private static CompoundTag worldData(Boolean marker, boolean startsWithTrain) {
        DungeonTrainWorldData data = DungeonTrainWorldData.createDefault();
        data.apply(data.getTrainY(), startsWithTrain, data.dims());
        if (marker != null) data.recordDungeonTrainPreset(marker);
        CompoundTag root = new CompoundTag();
        root.put("data", data.save(new CompoundTag(), null));
        root.putInt("DataVersion", 3955);
        return root;
    }

    /** What vanilla's Default type and DT's Compatible Terrain preset both store for the overworld. */
    private static CompoundTag vanillaLevelDat() {
        return levelDat("minecraft:overworld", "minecraft:overworld");
    }

    /** A vanilla overworld beside the Nether and End entries WorldWeaver writes into every save. */
    private static CompoundTag betterXLevelDat() {
        CompoundTag root = vanillaLevelDat();
        CompoundTag dimensions = root.getCompound("Data").getCompound("WorldGenSettings").getCompound("dimensions");
        dimensions.put("minecraft:the_nether", betterXDimension("minecraft:the_nether", "minecraft:nether", "wover:nether_biome_source"));
        dimensions.put("minecraft:the_end", betterXDimension("minecraft:the_end", "minecraft:end", "wover:end_biome_source"));
        return root;
    }

    private static CompoundTag betterXDimension(String dimensionType, String noiseSettings, String biomeSource) {
        CompoundTag source = new CompoundTag();
        source.putString("type", biomeSource);
        CompoundTag generator = new CompoundTag();
        generator.putString("type", "wover:betterx");
        generator.putString("settings", noiseSettings);
        generator.put("biome_source", source);
        CompoundTag dimension = new CompoundTag();
        dimension.putString("type", dimensionType);
        dimension.put("generator", generator);
        return dimension;
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
