package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * On-disk home for {@link ContentsEntitySnapshot} blobs — the mobs of a carriage group that the
 * distance gate has swept out of the level while no player is nearby.
 *
 * <h2>Why disk rather than the provider</h2>
 * The snapshot could live in a field on {@link TrainTransformProvider} beside
 * {@code pendingContentsEntitySpawns}. It does not, because the whole point of the sweep is that a
 * long train stops costing anything per carriage — holding every abandoned carriage's mob NBT in the
 * Java heap would trade a tick cost for a memory cost that grows with exactly the same term
 * (train length × mobs per carriage). Writing the blob out keeps the resident cost O(1).
 *
 * <h2>Layout</h2>
 * {@code <world_save>/dungeontrain/contents-despawn/<namespace>__<path>/<subLevelUuid>.nbt}. The dim
 * segment is sanitised the same way {@link CarriagePersistenceStore} does it — colons are reserved
 * on Windows.
 *
 * <h2>Why the key is the sub-level UUID</h2>
 * One carriage group is one Sable sub-level is one {@link TrainTransformProvider} is one snapshot,
 * so {@link games.brennan.dungeontrain.ship.ManagedShip#subLevelId()} is the natural identity. It is
 * also the <i>safe</i> identity: a rolling-window slot that gets recycled is assembled as a brand new
 * sub-level with a brand new UUID, so a stale snapshot can never be picked up by the carriage that
 * later occupies the same shipyard coordinates. Keying on {@code (trainId, pIdx)} would have exactly
 * that collision. {@code anchorPIdx} and {@code trainId} still ride <i>inside</i> the file for
 * diagnostics.
 *
 * <h2>Lifecycle</h2>
 * Written by {@link ContentsEntitySnapshot#captureAndDiscard}; deleted on successful restore, on
 * group cull (from {@code TrainCarriageAppender}'s unregister branch), and wholesale by
 * {@link #clear} from {@code TrainAssembler.deleteExistingTrains} beside
 * {@link CarriagePersistenceStore#clear}. {@link #pruneOrphans} reclaims anything a hard crash left
 * behind.
 *
 * <p><b>Not cross-restart state.</b> After a server restart no kinematic driver exists, so
 * {@code TrainBootstrapEvents} rebuilds the train from scratch and the bootstrap wipe empties this
 * directory. These files buy a bounded heap and crash-safety within a session; they are deliberately
 * not a save format.</p>
 */
public final class ContentsSnapshotStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SUBDIR = "dungeontrain/contents-despawn";
    private static final String EXT = ".nbt";

    private ContentsSnapshotStore() {}

    private static Path dimDirectory(ServerLevel level) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        ResourceLocation dim = level.dimension().location();
        String dimSeg = dim.getNamespace() + "__" + dim.getPath();
        return worldRoot.resolve(SUBDIR).resolve(dimSeg);
    }

    private static Path fileFor(ServerLevel level, UUID subLevelId) {
        return dimDirectory(level).resolve(subLevelId + EXT);
    }

    /**
     * Persist one group's snapshot.
     *
     * <p>Returns {@code false} on any I/O failure so the caller can <b>abort the sweep without
     * discarding the entities</b>. That is the one invariant worth being strict about here: the
     * degraded mode must be "the mobs stay live and keep costing tick time", never "the mobs are
     * gone". This is deliberately stricter than {@link CarriagePersistenceStore#save}, which
     * logs-and-continues because its fallback is regenerating the carriage — there is no
     * regeneration path for a swept mob's state.</p>
     */
    public static boolean write(ServerLevel level, ContentsEntitySnapshot snapshot) {
        Path file = fileFor(level, snapshot.subLevelId());
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(snapshot.toNbt(), file);
            return true;
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to write contents-despawn snapshot to {}: {}",
                file, e.toString());
            return false;
        }
    }

    /** Read a group's snapshot, or {@code null} if none exists / it could not be parsed. */
    public static ContentsEntitySnapshot read(ServerLevel level, UUID subLevelId) {
        Path file = fileFor(level, subLevelId);
        if (!Files.isRegularFile(file)) return null;
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            return ContentsEntitySnapshot.fromNbt(tag);
        } catch (IOException | RuntimeException e) {
            // RuntimeException covers a truncated / hand-edited file that parses as NBT but not as
            // our schema. Either way the snapshot is unrecoverable; drop the file so the group
            // stops retrying it every tick it is in restore range.
            LOGGER.warn("[DungeonTrain] Failed to read contents-despawn snapshot {}: {} — discarding",
                file, e.toString());
            delete(level, subLevelId);
            return null;
        }
    }

    /** Remove a group's snapshot. No-op when there isn't one. */
    public static void delete(ServerLevel level, UUID subLevelId) {
        Path file = fileFor(level, subLevelId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to delete contents-despawn snapshot {}: {}",
                file, e.toString());
        }
    }

    /**
     * Delete every snapshot for this level. Called from
     * {@code TrainAssembler.deleteExistingTrains} beside {@link CarriagePersistenceStore#clear} so a
     * fresh train — including the one the bootstrap builds on every server start — never inherits a
     * previous train's swept mobs.
     */
    public static void clear(ServerLevel level) {
        Path dim = dimDirectory(level);
        if (!Files.isDirectory(dim)) return;
        int deleted = deleteMatching(level, dim, path -> true);
        if (deleted > 0) {
            LOGGER.info("[DungeonTrain] Cleared {} contents-despawn snapshots under {}", deleted, dim);
        }
    }

    /**
     * Reclaim files left behind by a hard crash. Called once per level, from the despawn
     * controller's first reconcile — the point at which no snapshot from <i>this</i> session can
     * exist yet, so everything on disk is by definition stale and a blanket {@link #clear} is
     * provably safe.
     *
     * <p>Deliberately not "delete anything whose sub-level is not in {@code Shipyard.findAll()}":
     * {@code findAll} omits sub-levels that have been culled to holding, and a held group's snapshot
     * must survive until the group either resolves or is genuinely deleted.</p>
     */
    public static void pruneStaleFromPreviousSession(ServerLevel level) {
        clear(level);
    }

    /** Walk the dim dir and delete every {@code .nbt} whose bare filename passes {@code predicate}. */
    private static int deleteMatching(ServerLevel level, Path dim, java.util.function.Predicate<String> predicate) {
        int deleted = 0;
        try (Stream<Path> files = Files.list(dim)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(EXT)) continue;
                if (!predicate.test(name.substring(0, name.length() - EXT.length()))) continue;
                try {
                    if (Files.deleteIfExists(file)) deleted++;
                } catch (IOException ignore) {
                    // Per-file failures are non-fatal — the next clear retries.
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to list contents-despawn dir {}: {}", dim, e.toString());
        }
        return deleted;
    }
}
