package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Per-world per-carriage persistence. When the rolling-window manager
 * evicts a carriage that's fallen behind the player, we first snapshot
 * its current shipyard state (blocks + block-entity NBT) to disk via
 * {@link StructureTemplate#fillFromWorld}. When the player walks back
 * into range, {@link TrainWindowManager} calls {@link #restore} first
 * and only falls through to fresh variant generation if no snapshot
 * exists.
 *
 * <h2>Why</h2>
 * Before this, rolling-window eviction destroyed player edits (broken
 * window blocks, looted chests, repositioned armor stands). Now those
 * survive a round-trip — walk past a carriage, walk back, and see the
 * same state you left.
 *
 * <h2>Layout</h2>
 * {@code <world_save>/dungeontrain/carriage-persist/<dim>/<idx>.nbt}.
 * The dim segment distinguishes overworld/nether/end; the idx segment
 * is the signed integer carriage index (negative prefix is handled by
 * Java's {@link Integer#toString}, so e.g. {@code -42.nbt}).
 *
 * <h2>Lifecycle</h2>
 * Save on erase; load on place; {@link #clear} wipes the whole
 * directory when the train is reset or respawned via
 * {@link TrainAssembler#deleteExistingTrains}.
 */
public final class CarriagePersistenceStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SUBDIR = "dungeontrain/carriage-persist";
    private static final String EXT = ".nbt";
    // Trains only operate in a single dimension at a time, but the path
    // includes the dim namespace + path so a future multi-dim train does
    // not alias carriage index 5 across dimensions.

    private CarriagePersistenceStore() {}

    private static Path dimDirectory(ServerLevel level) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        ResourceLocation dim = level.dimension().location();
        // Sanitize the dim namespace:path for filesystem use — colons are
        // reserved on Windows. Use `__` so the resulting folder is
        // unambiguous ("minecraft__overworld", "dungeontrain__trainspace").
        String dimSeg = dim.getNamespace() + "__" + dim.getPath();
        return worldRoot.resolve(SUBDIR).resolve(dimSeg);
    }

    private static Path shipDirectory(ServerLevel level, long shipId) {
        return dimDirectory(level).resolve(Long.toString(shipId));
    }

    private static Path fileFor(ServerLevel level, long shipId, int carriageIdx) {
        return shipDirectory(level, shipId).resolve(carriageIdx + EXT);
    }

    /**
     * Capture the current shipyard-space carriage footprint and write it
     * to disk. Called just before {@link CarriageTemplate#eraseAt} so the
     * snapshot includes any player edits accumulated since the carriage
     * was placed. Silently logs-and-continues on I/O failure — a lost
     * snapshot downgrades the feature from "player edits preserved" to
     * "carriage regenerated from variant," which is the pre-persistence
     * behaviour.
     */
    public static void save(ServerLevel level, long shipId, int carriageIdx, BlockPos shipyardOrigin, CarriageDims dims) {
        Path file = fileFor(level, shipId, carriageIdx);
        try {
            Files.createDirectories(file.getParent());
            StructureTemplate template = new StructureTemplate();
            Vec3i size = new Vec3i(dims.length(), dims.height(), dims.width());
            // includeAir=true so we round-trip the empty interior volume
            // verbatim (an empty chest on a wall matters; so does the
            // absence of one). ignoreBlock=null means "keep everything."
            template.fillFromWorld(level, shipyardOrigin, size, true, null);
            CompoundTag tag = template.save(new CompoundTag());
            NbtIo.writeCompressed(tag, file);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to persist carriage idx={} to {}: {}",
                carriageIdx, file, e.toString());
        }
    }

    /**
     * If a snapshot exists for {@code carriageIdx}, stamp it at
     * {@code origin} and return true; otherwise return false so the
     * caller falls through to variant-based fresh generation.
     *
     * Stamps with {@link StructurePlaceSettings#setIgnoreEntities} set
     * to {@code false} — armor stands, item frames, etc. come back with
     * the carriage.
     */
    public static boolean restore(ServerLevel level, long shipId, int carriageIdx, BlockPos origin) {
        Path file = fileFor(level, shipId, carriageIdx);
        if (!Files.isRegularFile(file)) return false;
        try {
            CompoundTag tag = NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            StructureTemplate template = new StructureTemplate();
            HolderGetter<Block> blocks = level.holderLookup(Registries.BLOCK);
            template.load(blocks, tag);
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(false);
            template.placeInWorld(level, origin, origin, settings, level.getRandom(), 3);
            return true;
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to restore carriage idx={} from {}: {}",
                carriageIdx, file, e.toString());
            return false;
        }
    }

    /**
     * Delete every persisted carriage snapshot for this level's train.
     * Called from {@link TrainAssembler#deleteExistingTrains} so a fresh
     * spawn is not poisoned by snapshots from the previous train's ride.
     */
    public static void clear(ServerLevel level) {
        Path dim = dimDirectory(level);
        if (!Files.isDirectory(dim)) return;
        int[] deleted = {0};
        try {
            java.nio.file.Files.walkFileTree(dim, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(EXT)) {
                        try {
                            Files.deleteIfExists(file);
                            deleted[0]++;
                        } catch (IOException ignore) {
                            // Per-file failures are non-fatal.
                        }
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException exc) {
                    // Tidy up empty per-ship subdirectories.
                    if (!d.equals(dim)) {
                        try {
                            Files.deleteIfExists(d);
                        } catch (IOException ignore) {
                            // A non-empty dir (e.g. if another process wrote there)
                            // is harmless; subsequent saves will still work.
                        }
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to walk persistence dir {}: {}", dim, e.toString());
            return;
        }
        if (deleted[0] > 0) {
            LOGGER.info("[DungeonTrain] Cleared {} persisted carriage snapshots under {}", deleted[0], dim);
        }
    }
}
