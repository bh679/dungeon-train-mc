package games.brennan.dungeontrain.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The seal sets exactly one flag in {@code level.dat} and leaves everything else as it found it. */
class ExperimentalWarningSealTest {

    private static Path writeLevelDat(Path dir, boolean confirmed) throws Exception {
        CompoundTag data = new CompoundTag();
        data.putString("LevelName", "Dungeon Train");
        data.putLong("Time", 1234L);
        if (confirmed) data.putBoolean(ExperimentalWarningSeal.TAG_CONFIRMED, true);
        CompoundTag root = new CompoundTag();
        root.put(ExperimentalWarningSeal.TAG_DATA, data);
        Path file = dir.resolve(ExperimentalWarningSeal.LEVEL_DAT);
        NbtIo.writeCompressed(root, file);
        return file;
    }

    private static CompoundTag readData(Path file) throws Exception {
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound(ExperimentalWarningSeal.TAG_DATA);
    }

    @Test
    void setsTheFlagAndKeepsTheRest(@TempDir Path dir) throws Exception {
        Path file = writeLevelDat(dir, false);
        assertTrue(ExperimentalWarningSeal.confirm(dir));
        CompoundTag data = readData(file);
        assertTrue(data.getBoolean(ExperimentalWarningSeal.TAG_CONFIRMED));
        assertEquals("Dungeon Train", data.getString("LevelName"));
        assertEquals(1234L, data.getLong("Time"));
    }

    @Test
    void alreadyConfirmedIsANoOp(@TempDir Path dir) throws Exception {
        Path file = writeLevelDat(dir, true);
        long before = Files.getLastModifiedTime(file).toMillis();
        assertTrue(ExperimentalWarningSeal.confirm(dir));
        assertEquals(before, Files.getLastModifiedTime(file).toMillis(), "file not rewritten");
    }

    @Test
    void missingOrMalformedFileIsReportedNotThrown(@TempDir Path dir) throws Exception {
        assertFalse(ExperimentalWarningSeal.confirm(dir));
        Files.writeString(dir.resolve(ExperimentalWarningSeal.LEVEL_DAT), "not nbt");
        assertFalse(ExperimentalWarningSeal.confirm(dir));
    }
}
