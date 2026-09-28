package games.brennan.dungeontrain.data;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Marks a save as having already answered vanilla's "Worlds using Experimental Settings are not
 * supported — Create Backup and Load / I know what I'm doing!" card, so reopening it goes straight in.
 *
 * <p>Every Dungeon Train world is experimental by vanilla's definition (custom dimensions and
 * world-gen), and the card is exactly what a crash-recovery salvage must not put in front of the
 * player: they were <em>just</em> in this world, and "here be dragons" reads as a second thing
 * gone wrong. The flag is the same one the card's own "I know what I'm doing!" writes —
 * {@code Data.confirmedExperimentalSettings} in {@code level.dat} (NeoForge's addition, read by
 * {@code PrimaryLevelData.parse}) — set ahead of the open instead of after it.</p>
 *
 * <p>Only the one boolean is touched; the rest of {@code level.dat} is rewritten byte-for-byte
 * from what was read. Best-effort: a failure is logged and the open proceeds — the worst case is
 * the card showing, which is where we started. Minecraft-free apart from the NBT codec, so it is
 * drivable from JUnit on a synthetic {@code level.dat}.</p>
 */
public final class ExperimentalWarningSeal {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String LEVEL_DAT = "level.dat";
    static final String TAG_DATA = "Data";
    static final String TAG_CONFIRMED = "confirmedExperimentalSettings";

    private ExperimentalWarningSeal() {}

    /**
     * Set the confirmed flag in {@code <levelDir>/level.dat}.
     *
     * @return true when the file now carries the flag (already set, or set here); false when it
     *         couldn't be read or written
     */
    public static boolean confirm(Path levelDir) {
        Path file = levelDir.resolve(LEVEL_DAT);
        if (!Files.isRegularFile(file)) {
            LOGGER.warn("[DungeonTrain] Crash recovery: no {} under {}; can't pre-confirm the experimental-settings card.", LEVEL_DAT, levelDir);
            return false;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            if (!root.contains(TAG_DATA, CompoundTag.TAG_COMPOUND)) {
                LOGGER.warn("[DungeonTrain] Crash recovery: {} has no '{}' compound; leaving it alone.", file, TAG_DATA);
                return false;
            }
            CompoundTag data = root.getCompound(TAG_DATA);
            if (data.getBoolean(TAG_CONFIRMED)) return true;
            data.putBoolean(TAG_CONFIRMED, true);
            NbtIo.writeCompressed(root, file);
            LOGGER.info("[DungeonTrain] Crash recovery: pre-confirmed the experimental-settings card for {}", levelDir.getFileName());
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Crash recovery: couldn't pre-confirm the experimental-settings card in {}: {}", file, e.toString());
            return false;
        }
    }
}
