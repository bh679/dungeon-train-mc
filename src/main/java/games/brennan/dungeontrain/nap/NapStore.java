package games.brennan.dungeontrain.nap;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One pending nap per player and {@link NapSlot}, at {@code <root>/naps/<uuid>-<slot>.dat}. Singleplayer's integrated server and
 * client share the game directory, so the server writes it and the next run's server reads it; the
 * {@code root} parameter is where a multiplayer server would keep its own.
 *
 * <p>Waking deletes the file and appends the nap's id to {@code naps-applied.txt} beside it, so a copy
 * of the file restored by hand is refused.</p>
 */
public final class NapStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String DIR = "naps";
    private static final String APPLIED = "naps-applied.txt";

    private final Path dir;

    public NapStore(Path root) {
        this.dir = root.resolve(DIR);
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
            if (appliedIds().contains(nap.napId().toString())) {
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
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(APPLIED), nap.napId() + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Files.deleteIfExists(fileFor(player, slot));
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

    private Set<String> appliedIds() throws IOException {
        Path applied = dir.resolve(APPLIED);
        if (!Files.isRegularFile(applied)) return Set.of();
        Set<String> ids = new HashSet<>();
        for (String line : Files.readAllLines(applied, StandardCharsets.UTF_8)) {
            String id = line.trim();
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }
}
