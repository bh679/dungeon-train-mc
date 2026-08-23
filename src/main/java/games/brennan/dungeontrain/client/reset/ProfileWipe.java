package games.brennan.dungeontrain.client.reset;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The "start over" behind the Video Tools reset button: work out what a fresh start would have to
 * remove ({@link #survey}), then remove it ({@link #execute}).
 *
 * <p>Two halves, because the confirm screen shows the survey verbatim before anything is touched —
 * the player reads the exact world names that are about to go. This is a permanent delete with no
 * backup, so the survey <em>is</em> the safety net.</p>
 *
 * <p>Client-side and title-screen-only: no server is running, so files are the whole truth and the
 * stores' in-memory caches are dropped by their own {@code deleteFor} (see {@link ProfileItem}).</p>
 *
 * <p><b>What counts as a Dungeon Train world:</b> a save with {@code data/dungeontrain_world.dat} in
 * it — the {@link DungeonTrainWorldData} the mod writes into the overworld. That is "has DT state",
 * not "was created by DT", so a pre-existing vanilla world opened once with the mod installed will
 * be listed too. Saves without it are never touched.</p>
 */
public final class ProfileWipe {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Overworld saved-data lives at {@code <save>/data/<name>.dat}. */
    private static final String DATA_DIR = "data";
    private static final String DT_MARKER_FILE = DungeonTrainWorldData.NAME + ".dat";

    /** A save folder in the world list. {@code levelId} is the folder name deletion works on. */
    public record World(String levelId, String displayName) {}

    /**
     * What a reset would do, as shown on the confirm screen.
     *
     * @param dtWorlds     saves that will be deleted
     * @param keptWorlds   saves that will be left alone, by display name
     * @param profileItems cross-world profile pieces that have something to remove
     */
    public record Survey(List<World> dtWorlds, List<String> keptWorlds, List<ProfileItem> profileItems) {
        public boolean isEmpty() {
            return dtWorlds.isEmpty() && profileItems.isEmpty();
        }
    }

    /** What a reset actually did. {@code failures} are already-localised-enough one-liners. */
    public record Result(int worldsDeleted, int itemsDeleted, List<String> failures) {}

    private ProfileWipe() {}

    /** Read-only: never touches a file. Safe to call on every screen open. */
    public static Survey survey() {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc.getUser().getProfileId();

        List<World> dtWorlds = new ArrayList<>();
        List<String> keptWorlds = new ArrayList<>();
        for (LevelStorageSource.LevelDirectory dir : levelDirectories(mc)) {
            String name = displayName(dir);
            if (Files.isRegularFile(dir.path().resolve(DATA_DIR).resolve(DT_MARKER_FILE))) {
                dtWorlds.add(new World(dir.directoryName(), name));
            } else {
                keptWorlds.add(name);
            }
        }

        List<ProfileItem> items = new ArrayList<>();
        for (ProfileItem item : ProfileItem.values()) {
            if (item.present(uuid)) {
                items.add(item);
            }
        }
        return new Survey(List.copyOf(dtWorlds), List.copyOf(keptWorlds), List.copyOf(items));
    }

    /**
     * Delete everything in {@code survey}, permanently. One failure never stops the rest — a locked
     * save or an IO error is logged, collected, and reported back to the player, because a half-done
     * reset the player knows about beats a half-done reset they don't.
     */
    public static Result execute(Survey survey) {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc.getUser().getProfileId();
        List<String> failures = new ArrayList<>();

        int worlds = 0;
        for (World world : survey.dtWorlds()) {
            if (deleteWorld(mc, world, failures)) {
                worlds++;
            }
        }

        int items = 0;
        for (ProfileItem item : survey.profileItems()) {
            try {
                if (item.delete(uuid)) {
                    items++;
                }
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] ProfileWipe: failed to clear {}", item, e);
                failures.add(item.label().getString() + " — " + reason(e));
            }
        }

        LOGGER.info("[DungeonTrain] ProfileWipe: deleted {} world(s), cleared {} profile item(s), {} failure(s)",
            worlds, items, failures.size());
        return new Result(worlds, items, List.copyOf(failures));
    }

    /** Vanilla's own world-list deletion path, as used by the death screen's discard-world flow. */
    private static boolean deleteWorld(Minecraft mc, World world, List<String> failures) {
        try (LevelStorageSource.LevelStorageAccess access = mc.getLevelSource().createAccess(world.levelId())) {
            access.deleteLevel();
            return true;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] ProfileWipe: failed to delete world '{}'", world.levelId(), e);
            failures.add(world.displayName() + " — " + reason(e));
            return false;
        }
    }

    /** Save folders in the world list, or empty when the saves dir can't be read at all. */
    private static List<LevelStorageSource.LevelDirectory> levelDirectories(Minecraft mc) {
        try {
            return mc.getLevelSource().findLevelCandidates().levels();
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] ProfileWipe: could not list world saves", e);
            return List.of();
        }
    }

    /**
     * The world's in-game name, read straight out of {@code level.dat}. Deliberately not
     * {@code loadLevelSummaries} — that runs the data fixers on a worker per save, and all this
     * screen needs is a label. Falls back to the folder name, which is what the player sees on disk.
     */
    private static String displayName(LevelStorageSource.LevelDirectory dir) {
        Path dataFile = dir.dataFile();
        if (Files.isRegularFile(dataFile)) {
            try {
                CompoundTag root = NbtIo.readCompressed(dataFile, NbtAccounter.unlimitedHeap());
                String name = root.getCompound("Data").getString("LevelName");
                if (!name.isBlank()) {
                    return name;
                }
            } catch (Exception e) {
                LOGGER.debug("[DungeonTrain] ProfileWipe: unreadable level.dat for '{}'", dir.directoryName(), e);
            }
        }
        return dir.directoryName();
    }

    /** Short, player-readable failure tail — the stack trace is in the log, not on the screen. */
    private static String reason(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
