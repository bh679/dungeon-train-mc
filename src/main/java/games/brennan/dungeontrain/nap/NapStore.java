package games.brennan.dungeontrain.nap;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One pending nap per player and {@link NapSlot}, at {@code <root>/naps/<uuid>-<slot>.dat}. Singleplayer's integrated server and
 * client share the game directory, so the server writes it and the next run's server reads it; the
 * {@code root} parameter is where a multiplayer server would keep its own.
 *
 * <p>Saving makes the nap its slot's pending one in {@link NapAppliedLog} (sealed under the install
 * key) before the file is written; a nap wakes only while it is still pending there. Waking records it
 * as woken and deletes the file, so a copy restored by hand, a nap whose id was edited, or any nap
 * once the list was deleted or rebuilt, is refused. Files this version writes carry {@link #MARK}; only
 * unmarked naps (saved before the upgrade) can be adopted when the list is first moved to v3.</p>
 */
public final class NapStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String DIR = "naps";
    /** Tag on every nap file this version writes: never adopted by a list migration. */
    static final String MARK = "napLedger";
    private final Path dir;
    private final NapAppliedLog applied;

    public NapStore(Path root) {
        this.dir = root.resolve(DIR);
        this.applied = new NapAppliedLog(dir, this::adoptLegacy);
    }

    /** {@code root} with the list's clock pinned, for tests of a damaged list. */
    NapStore(Path root, java.util.function.LongSupplier clock) {
        this.dir = root.resolve(DIR);
        this.applied = new NapAppliedLog(dir, clock, this::adoptLegacy);
    }

    /** The applied list's key for one player's slot. */
    static String key(UUID player, String slot) {
        return player + "-" + slot;
    }

    /** The store in this install's {@code dungeontrain/} folder. */
    public static NapStore local() {
        return new NapStore(PlayerDataPaths.root());
    }

    Path fileFor(UUID player, String slot) {
        if (!slot.matches("[a-z_]+-[a-z_]+")) throw new IllegalArgumentException("bad nap slot " + slot);
        return dir.resolve(player + "-" + slot + ".dat");
    }

    /**
     * Makes {@code nap} the slot's pending nap, then writes it atomically (temp file then move) so a
     * crash never leaves half a nap. A list that can't be written fails the save.
     */
    public void save(UUID player, String slot, NapSnapshot nap) throws IOException {
        Path target = fileFor(player, slot);
        applied.register(key(player, slot), nap.napId());
        writeFile(target, nap);
    }

    private void writeFile(Path target, NapSnapshot nap) throws IOException {
        Files.createDirectories(dir);
        Path tmp = dir.resolve(target.getFileName() + ".tmp");
        CompoundTag tag = nap.toTag();
        tag.putByte(MARK, (byte) 1);
        NbtIo.writeCompressed(tag, tmp);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The pending nap, unless there is none, it doesn't read, or it already woke once. */
    public Optional<NapSnapshot> load(UUID player, String slot) {
        Path file = fileFor(player, slot);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            NapSnapshot nap = NapSnapshot.fromTag(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
            if (nap == null) {
                LOGGER.warn("[DungeonTrain] Nap file {} is not a nap; ignoring it.", file);
                return Optional.empty();
            }
            NapAppliedLog.State list = applied.read();
            if (list.woken().contains(nap.napId())) {
                LOGGER.warn("[DungeonTrain] Nap {} already woke once; discarding the copy.", nap.napId());
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            if (!list.allows(key(player, slot), nap.napId())) {
                LOGGER.warn("[DungeonTrain] Nap {} is not the waiting nap on the nap list; not waking it (kept).",
                    nap.napId());
                return Optional.empty();
            }
            return Optional.of(nap);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not read nap file {}", file, e);
            return Optional.empty();
        }
    }

    /**
     * Marks the nap woken, then removes its file. The mark goes first so a failed delete can't wake it
     * again; the file goes even when the mark fails, since its gear is in the world by now and a file
     * left behind could wake once that world is gone. A failure is thrown after the delete.
     */
    public void consume(UUID player, String slot, NapSnapshot nap) throws IOException {
        IOException failure = null;
        try {
            applied.markWoken(key(player, slot), nap.napId());
        } catch (IOException e) {
            failure = e;
        } catch (RuntimeException e) {
            failure = new IOException("nap list could not be updated", e);
        }
        try {
            Files.deleteIfExists(fileFor(player, slot));
        } catch (IOException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        if (failure != null) throw failure;
    }

    /**
     * Ties the pending nap to the world it is waking in ({@link NapBinding}), atomically, before the
     * world gets the train or the player. Returns the bound nap.
     */
    public NapSnapshot bind(UUID player, String slot, NapSnapshot nap, String levelId) throws IOException {
        NapSnapshot bound = nap.boundTo(levelId);
        save(player, slot, bound);
        return bound;
    }

    /**
     * For a list's move to v3: the waiting naps from before the upgrade (unmarked) that the old list
     * would have woken, as pending entries. Marked naps are never adopted.
     */
    private Map<String, UUID> adoptLegacy(NapAppliedLog.Legacy legacy) {
        Map<String, UUID> out = new HashMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".dat") || name.length() <= 37 || name.charAt(36) != '-') continue;
                String key = name.substring(0, name.length() - ".dat".length());
                try {
                    UUID.fromString(name.substring(0, 36));
                    if (!key.substring(37).matches("[a-z_]+-[a-z_]+")) continue;
                    CompoundTag tag = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
                    if (tag.contains(MARK)) continue;
                    NapSnapshot nap = NapSnapshot.fromTag(tag);
                    if (nap == null || legacy.ids().contains(nap.napId()) || !legacy.trusts(nap)) continue;
                    out.put(key, nap.napId());
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("[DungeonTrain] Could not read nap file {} for the list move; not carried over", f, e);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not list naps in {}", dir, e);
        }
        return out;
    }

    /** One pending nap and the slot it sits in. */
    public record Pending(String slot, NapSnapshot nap) {}

    /** Every pending nap of {@code player}, whatever its slot (same single-use checks as {@link #load}). */
    public List<Pending> pending(UUID player) {
        List<Pending> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        String prefix = player + "-";
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String name = f.getFileName().toString();
                if (!name.startsWith(prefix) || !name.endsWith(".dat")) continue;
                String slot = name.substring(prefix.length(), name.length() - ".dat".length());
                if (!slot.matches("[a-z_]+-[a-z_]+")) continue;
                load(player, slot).ifPresent(nap -> out.add(new Pending(slot, nap)));
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not list naps in {}", dir, e);
        }
        return out;
    }

    /** Whether a nap file sits in {@code slot}, woken or not. */
    public boolean hasFile(UUID player, String slot) {
        return Files.isRegularFile(fileFor(player, slot));
    }

    /** Save folders named by a pending nap: worlds that must not survive it. */
    public Set<String> pendingLevelIds() {
        Set<String> out = new HashSet<>();
        if (!Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.getFileName().toString().endsWith(".dat"))::iterator) {
                try {
                    NapSnapshot nap = NapSnapshot.fromTag(NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap()));
                    if (nap != null && !nap.levelId().isEmpty()) out.add(nap.levelId());
                } catch (IOException e) {
                    LOGGER.warn("[DungeonTrain] Could not read nap file {}", f, e);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not list naps in {}", dir, e);
        }
        return out;
    }
}
