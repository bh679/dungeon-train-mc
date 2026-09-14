package games.brennan.dungeontrain.player;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Remembers that a player has expanded their Free Play Ender Chest, at
 * {@code <instance>/dungeontrain/enderchest/<uuid>.dat}.
 *
 * <p>One flag per player, per install — the expansion is a property of the player's Free Play locker,
 * which EnderChestPersistence already keeps across worlds, so this has to outlive the world too. A
 * {@code SavedData} would be thrown away with the run.</p>
 *
 * <p>Deliberately tiny and forgiving. The flag is an <em>optimisation</em> of the truth, not the truth:
 * a chest that already holds items beyond slot 27 is expanded whether or not this file says so (see
 * {@code EnderChestExpansion}), so losing it can never shrink a chest around a player's items. Every
 * operation is best-effort — an I/O failure is logged and the player simply sees the button again.</p>
 *
 * <p>Path-parameterised so a test can point it at a temp dir; production uses {@link #DEFAULT}.</p>
 */
public final class EnderChestExpansionStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TAG_EXPANDED = "FreePlayExpanded";

    /** The install-wide store, under {@link PlayerDataPaths#ENDER_CHEST}. Resolved lazily — see {@link #dir}. */
    public static final EnderChestExpansionStore DEFAULT = new EnderChestExpansionStore(null);

    private final Path fixedDir;

    /** @param dir the folder holding the per-player files, or null to use {@link PlayerDataPaths}. */
    public EnderChestExpansionStore(Path dir) {
        this.fixedDir = dir;
    }

    /** The file holding {@code uuid}'s flag. */
    public Path file(UUID uuid) {
        return dir().resolve(uuid + ".dat");
    }

    /** Whether {@code uuid} has recorded an expansion. False when nothing is stored or it can't be read. */
    public boolean isExpanded(UUID uuid) {
        Path path = file(uuid);
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            return root.getBoolean(TAG_EXPANDED);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] could not read Ender Chest expansion flag {} — treating as not expanded",
                path, e);
            return false;
        }
    }

    /** Record that {@code uuid} expanded their Free Play chest. Best-effort; returns whether it was written. */
    public boolean markExpanded(UUID uuid) {
        return setExpanded(uuid, true);
    }

    /** Record that {@code uuid} shrank their chest back. Best-effort; returns whether it was written. */
    public boolean clearExpanded(UUID uuid) {
        return setExpanded(uuid, false);
    }

    private boolean setExpanded(UUID uuid, boolean expanded) {
        Path path = file(uuid);
        CompoundTag root = new CompoundTag();
        root.putBoolean(TAG_EXPANDED, expanded);
        try {
            Files.createDirectories(path.getParent());
            // Write-then-move so a crash mid-write can't leave a truncated file behind.
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            NbtIo.writeCompressed(root, tmp);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[DungeonTrain] could not write Ender Chest expansion flag {}", path, e);
            return false;
        }
    }

    private Path dir() {
        // Player data lives outside config/ — a modpack update replaces that folder. See PlayerDataPaths.
        return fixedDir != null ? fixedDir : PlayerDataPaths.dir(PlayerDataPaths.ENDER_CHEST);
    }
}
