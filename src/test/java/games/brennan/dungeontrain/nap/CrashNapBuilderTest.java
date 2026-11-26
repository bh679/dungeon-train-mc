package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.difficulty.BoardingProgressData;
import games.brennan.dungeontrain.narrative.NarrativeProgressData;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A crashed save becomes the nap Reboard wakes: the train X is the last one recorded on the train's
 * level, the depth and slot are the run's, and a run that can't come back is refused.
 */
class CrashNapBuilderTest {

    private static final UUID OWNER = UUID.fromString("380df991-f603-344c-a090-369bad2a924a");
    private static final String LEVEL = "Dungeon Train 1764118560000";
    private static final long NOW = 1_764_118_999_000L;
    private static final int HARD = 3;

    private static CrashNapBuilder.Result build(Path dir) {
        return CrashNapBuilder.build(dir, LEVEL, OWNER, "test", null, NOW);
    }

    private static NapSnapshot built(CrashNapBuilder.Result r) {
        return assertInstanceOf(CrashNapBuilder.Built.class, r).nap();
    }

    private static CompoundTag player(double x, String dim, float health, float yaw) {
        CompoundTag p = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(80));
        pos.add(DoubleTag.valueOf(3));
        p.put("Pos", pos);
        ListTag rot = new ListTag();
        rot.add(FloatTag.valueOf(yaw));
        rot.add(FloatTag.valueOf(0));
        p.put("Rotation", rot);
        p.putString("Dimension", dim);
        p.putFloat("Health", health);
        p.putString("marker", "inventory-here");
        return p;
    }

    private static void level(Path dir, CompoundTag embedded, int difficulty) throws Exception {
        CompoundTag data = new CompoundTag();
        data.putByte("Difficulty", (byte) difficulty);
        if (embedded != null) data.put("Player", embedded);
        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        NbtIo.writeCompressed(root, dir.resolve("level.dat"));
    }

    private static void saved(Path dir, String name, CompoundTag data) throws Exception {
        Path d = dir.resolve("data");
        Files.createDirectories(d);
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        NbtIo.writeCompressed(root, d.resolve(name + ".dat"));
    }

    private static void gate(Path dir, double lastTrainX, int napSaves) throws Exception {
        CompoundTag e = new CompoundTag();
        if (!Double.isNaN(lastTrainX)) e.putDouble("lastTrainX", lastTrainX);
        if (napSaves > 0) e.putInt("napSaves", napSaves);
        CompoundTag all = new CompoundTag();
        all.put(OWNER.toString(), e);
        saved(dir, NapGateData.NAME, all);
    }

    /** Kyrielle's run: woken at ~56k, last on the train at 56,231, carriage counter 1640. */
    private static void crashedRun(Path dir) throws Exception {
        level(dir, player(56_240.5, "minecraft:overworld", 14f, -90f), HARD);
        gate(dir, 56_231.25, 2);
        CompoundTag progress = new CompoundTag();
        progress.putInt("travelledCarriageIndex", 1640);
        saved(dir, BoardingProgressData.NAME, progress);
        CompoundTag world = new CompoundTag();
        world.putBoolean("startsWithTrain", true);
        world.putInt("difficultyTravelledOffset", 0);
        saved(dir, DungeonTrainWorldData.NAME, world);
    }

    @Test
    void crashedRunBecomesANapAtTheLastTrainX(@TempDir Path dir) throws Exception {
        crashedRun(dir);
        CrashNapBuilder.Built b = assertInstanceOf(CrashNapBuilder.Built.class, build(dir));
        NapSnapshot nap = b.nap();
        assertEquals(56_231.25, nap.x(), 1e-9);
        assertEquals(1640, nap.worldDepth());
        assertEquals("survival-hard", b.slot());
        assertEquals("hard", nap.difficulty());
        assertFalse(nap.cheated());
        assertEquals(LEVEL, nap.levelId());
        assertFalse(nap.bound());
        assertEquals("inventory-here", nap.player().getString("marker"));
        assertEquals(1, nap.direction(), "yaw -90 faces +X");
        assertEquals((int) Math.floor(56_231.25 / NapService.BLOCKS_PER_CARRIAGE), nap.carriage());
    }

    @Test
    void crashIsNotCountedAsANapSave(@TempDir Path dir) throws Exception {
        crashedRun(dir);
        assertEquals(2, PlayerRunState.napSavesIn(built(build(dir)).runExtras()));
    }

    @Test
    void withNoRecordedTrainXTheSavedOverworldPositionIsUsed(@TempDir Path dir) throws Exception {
        crashedRun(dir);
        gate(dir, Double.NaN, 0);
        assertEquals(56_240.5, built(build(dir)).x(), 1e-9);
    }

    @Test
    void aPlayerInAnotherDimensionWithNoTrainXIsRefused(@TempDir Path dir) throws Exception {
        crashedRun(dir);
        level(dir, player(120, "minecraft:the_nether", 14f, 0f), HARD);
        gate(dir, Double.NaN, 0);
        assertInstanceOf(CrashNapBuilder.Refused.class, build(dir));
    }

    @Test
    void aPlayerInAnotherDimensionUsesTheTrainX(@TempDir Path dir) throws Exception {
        crashedRun(dir);
        level(dir, player(120, "minecraft:the_nether", 14f, 0f), HARD);
        assertEquals(56_231.25, built(build(dir)).x(), 1e-9);
    }

    @Test
    void aPlotGridPositionIsNotATrainX(@TempDir Path dir) throws Exception {
        crashedRun(dir);
        level(dir, player(20_000_128, "minecraft:overworld", 14f, 0f), HARD);
        gate(dir, Double.NaN, 0);
        assertInstanceOf(CrashNapBuilder.Refused.class, build(dir));
    }

    @Test
    void aDeadPlayerIsRefused(@TempDir Path dir) throws Exception {
        crashedRun(dir);
        level(dir, player(56_240.5, "minecraft:overworld", 0f, 0f), HARD);
        assertInstanceOf(CrashNapBuilder.Refused.class, build(dir));
    }

    @Test
    void aWorldDtDidNotNameIsRefused(@TempDir Path dir) throws Exception {
        crashedRun(dir);
        assertInstanceOf(CrashNapBuilder.Refused.class,
            CrashNapBuilder.build(dir, "My Castle", OWNER, "test", null, NOW));
    }

    @Test
    void aWorldWithoutATrainIsRefused(@TempDir Path dir) throws Exception {
        crashedRun(dir);
        CompoundTag world = new CompoundTag();
        world.putBoolean("startsWithTrain", false);
        saved(dir, DungeonTrainWorldData.NAME, world);
        assertInstanceOf(CrashNapBuilder.Refused.class, build(dir));
    }

    @Test
    void noSavedPlayerIsRefused(@TempDir Path dir) throws Exception {
        crashedRun(dir);
        level(dir, null, HARD);
        assertInstanceOf(CrashNapBuilder.Refused.class, build(dir));
    }

    @Test
    void thePlayerdataFileIsUsedWhenLevelDatHoldsNoPlayer(@TempDir Path dir) throws Exception {
        crashedRun(dir);
        level(dir, null, HARD);
        Path pd = dir.resolve("playerdata");
        Files.createDirectories(pd);
        NbtIo.writeCompressed(player(56_240.5, "minecraft:overworld", 9f, 0f), pd.resolve(OWNER + ".dat"));
        assertEquals("inventory-here", built(build(dir)).player().getString("marker"));
    }

    @Test
    void aFreePlayRunGoesToTheFreePlaySlot(@TempDir Path dir) throws Exception {
        crashedRun(dir);
        CompoundTag p = player(56_240.5, "minecraft:overworld", 14f, 0f);
        p.putInt("playerGameType", 1);
        level(dir, p, HARD);
        CrashNapBuilder.Built b = assertInstanceOf(CrashNapBuilder.Built.class, build(dir));
        assertTrue(b.nap().cheated());
        assertEquals("freeplay-hard", b.slot());
    }

    @Test
    void aPerksTestWorldKeepsItsOwnSlot(@TempDir Path dir) throws Exception {
        crashedRun(dir);
        CompoundTag world = new CompoundTag();
        world.putBoolean("startsWithTrain", true);
        world.putBoolean("perksTest", true);
        saved(dir, DungeonTrainWorldData.NAME, world);
        CrashNapBuilder.Built b = assertInstanceOf(CrashNapBuilder.Built.class, build(dir));
        assertEquals("survival-perktest", b.slot());
        assertEquals("perktest", b.nap().difficulty());
    }

    @Test
    void theDifficultyOffsetAndPlayerFilesAreCarried(@TempDir Path dir) throws Exception {
        crashedRun(dir);
        CompoundTag world = new CompoundTag();
        world.putBoolean("startsWithTrain", true);
        world.putInt("difficultyTravelledOffset", 250);
        saved(dir, DungeonTrainWorldData.NAME, world);
        Files.createDirectories(dir.resolve("advancements"));
        Files.writeString(dir.resolve("advancements").resolve(OWNER + ".json"), "{\"adv\":1}");
        Files.createDirectories(dir.resolve("stats"));
        Files.writeString(dir.resolve("stats").resolve(OWNER + ".json"), "{\"stats\":1}");
        NapSnapshot nap = built(build(dir));
        assertEquals(250, nap.difficultyOffset());
        assertEquals("{\"adv\":1}", nap.advancementsJson());
        assertEquals("{\"stats\":1}", nap.statsJson());
    }

    @Test
    void theNapSurvivesItsOwnFileFormat(@TempDir Path dir) throws Exception {
        crashedRun(dir);
        NapSnapshot nap = built(build(dir));
        NapSnapshot back = NapSnapshot.fromTag(nap.toTag());
        assertNotNull(back);
        assertEquals(nap.x(), back.x(), 1e-9);
        assertEquals(nap.worldDepth(), back.worldDepth());
    }

    @Test
    void aBandPlaceAheadOfTheOriginRebuildsTheSameX() {
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        CompoundTag world = new CompoundTag();
        NapSnapshot.Place place = CrashNapBuilder.placeAt(cycle, 56_231.25, world);
        if (cycle.bandPosition(56_231) == null) {
            assertNull(place);
            return;
        }
        assertNotNull(place);
        assertEquals(56_231L, cycle.worldXOf(place.position()));
        assertEquals(Long.MAX_VALUE, place.originX());
    }

    @Test
    void noCycleRecordsNoPlace() {
        assertNull(CrashNapBuilder.placeAt(null, 56_231.25, new CompoundTag()));
    }

    @Test
    void narrativeExportIsEmptyWithoutSavedData() {
        assertTrue(NarrativeProgressData.exportFrom(new CompoundTag(), OWNER).isEmpty());
    }
}
