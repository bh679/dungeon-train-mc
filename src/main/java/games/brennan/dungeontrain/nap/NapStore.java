package games.brennan.dungeontrain.nap;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One pending nap per player and {@link NapSlot}, at {@code <root>/naps/<uuid>-<slot>.dat}. Singleplayer's integrated server and
 * client share the game directory, so the server writes it and the next run's server reads it; the
 * {@code root} parameter is where a multiplayer server would keep its own.
 *
 * <p>Waking records the nap's id in {@link NapAppliedLog} beside it, then deletes the file, so a copy
 * restored by hand is refused. If that record fails its check it is restarted and only naps saved
 * after that wake ({@link NapAppliedLog}).</p>
 */
public final class NapStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String DIR = "naps";
    private final Path dir;
    private final NapAppliedLog applied;

    public NapStore(Path root) {
        this.dir = root.resolve(DIR);
        this.applied = new NapAppliedLog(dir);
    }

    /** The store in this install's {@code dungeontrain/} folder. */
    public static NapStore local() {
        return new NapStore(PlayerDataPaths.root());
    }

    Path fileFor(UUID player, String slot) {
        if (!slot.matches("[a-z_]+-[a-z_]+")) throw new IllegalArgumentException("bad nap slot " + slot);
        return dir.resolve(player + "-" + slot + ".dat");
    }

    /** Writes atomically (temp file then move) so a crash never leaves half a nap. */
    public void save(UUID player, String slot, NapSnapshot nap) throws IOException {
        Files.createDirectories(dir);
        Path target = fileFor(player, slot);
        Path tmp = dir.resolve(target.getFileName() + ".tmp");
        NbtIo.writeCompressed(nap.toTag(), tmp);
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
            NapAppliedLog.State used = applied.read();
            if (!used.trusts(nap)) {
                LOGGER.warn("[DungeonTrain] Nap {} was saved before the applied list was restarted; not waking it (kept).",
                    nap.napId());
                return Optional.empty();
            }
            if (used.ids().contains(nap.napId())) {
                LOGGER.warn("[DungeonTrain] Nap {} already woke once; discarding the copy.", nap.napId());
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            return Optional.of(nap);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not read nap file {}", file, e);
            return Optional.empty();
        }
    }

    /** Marks the nap used, then removes its file. The mark goes first so a failed delete can't wake it again. */
    public void consume(UUID player, String slot, NapSnapshot nap) throws IOException {
        applied.add(nap.napId());
        Files.deleteIfExists(fileFor(player, slot));
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
