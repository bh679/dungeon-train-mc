package games.brennan.dungeontrain.nap;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Whether the world a nap is bound to holds that life. A nap is bound to its new world at server
 * start, before the player joins; the gear is only in that world once the woken player has been
 * saved there. A bound world with no saved player (the game died before the join) holds nothing, so
 * the nap is free to wake in the next world rather than stranded. Shared by the server
 * ({@link NapWake}) and the title screen's Reboard check.
 */
public final class NapBoundWorld {

    private static final Logger LOGGER = LogUtils.getLogger();

    private NapBoundWorld() {}

    /**
     * True when {@code <savesDir>/<levelId>} is a world that holds {@code owner}'s saved player
     * data. A level.dat that can't be read counts as holding: leaving the nap to that world can
     * strand it, but waking it elsewhere could duplicate the gear.
     */
    public static boolean holdsLife(Path savesDir, String levelId, UUID owner) {
        if (savesDir == null || levelId == null || levelId.isEmpty()) return false;
        Path saves = savesDir.toAbsolutePath().normalize();
        Path world = saves.resolve(levelId).normalize();
        if (!saves.equals(world.getParent())) return false;
        Path levelDat = world.resolve("level.dat");
        if (!Files.isRegularFile(levelDat)) return false;
        if (owner != null && Files.isRegularFile(world.resolve("playerdata").resolve(owner + ".dat"))) return true;
        try {
            CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
            return root.getCompound("Data").contains("Player", Tag.TAG_COMPOUND);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Couldn't read {}; treating its nap as held there.", levelDat, e);
            return true;
        }
    }
}
