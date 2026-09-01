package games.brennan.dungeontrain.builder.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.data.PlayerDataBackup;
import games.brennan.dungeontrain.data.PlayerDataBackupHook;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where a build's template NBT can be read from, when the build has to go back up.
 *
 * <p>Two places, and the order matters. The <b>live store</b> is the build: what the player has now,
 * and what a re-upload should carry. A <b>backup archive</b> is the fallback for a build that is gone
 * from disk as well as from the relay — read out of the zip and uploaded, never written back into the
 * store. Putting it back on disk is {@code /dtrestore}'s job and is a different decision: this path
 * runs for builds whose local file may have been deleted deliberately, and resurrecting those without
 * being asked would undo the player's own housekeeping.</p>
 *
 * <p>Every lookup here is by the {@code (kind, subKind, id)} triple {@code BuilderSave.Written}
 * carries and {@link BuilderRelayBuilds#keyOf} files an upload under, so a build found here is the
 * same build the relay recorded.</p>
 */
public final class BuilderTemplateSource {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BuilderTemplateSource() {}

    /** Where a template's NBT came from — what the player is told before anything is uploaded. */
    public enum Origin {
        /** The live store: the file is on disk right now. */
        LIVE,
        /** A backup archive: the file is gone from disk and this is a copy of it. */
        BACKUP
    }

    /**
     * One readable template.
     *
     * @param tag     the template NBT, as a store writes it
     * @param origin  which tier it came from
     * @param archive the archive it was read out of, or null for {@link Origin#LIVE}
     */
    public record Found(CompoundTag tag, Origin origin, Path archive) {
        public boolean fromBackup() {
            return origin == Origin.BACKUP;
        }
    }

    /**
     * The file a build of {@code kind} is stored in — the same resolution
     * {@link BuilderPhotoPaths#photoFor} does, before it swaps the extension.
     *
     * <p>Empty when the kind needs a sub kind this one doesn't name: a part belongs to one of the
     * part kinds and a track to one of the track kinds, and without knowing which there is no
     * directory to look in. Guessing would read someone else's {@code default}.</p>
     */
    public static Optional<Path> fileFor(BuilderPhotoPaths.Kind kind, String subKind, String id) {
        if (kind == null || id == null || id.isEmpty()) return Optional.empty();
        return switch (kind) {
            case CARRIAGE -> Optional.of(CarriageTemplateStore.fileForId(id));
            case CARRIAGE_GROUP -> Optional.of(CarriageGroupTemplateStore.fileForId(id));
            case CONTENTS -> Optional.of(CarriageContentsStore.fileForId(id));
            case PART -> {
                CarriagePartKind partKind = CarriagePartKind.fromId(subKind);
                yield partKind == null
                        ? Optional.empty()
                        : Optional.of(CarriagePartTemplateStore.fileFor(partKind, id));
            }
            case TRACK -> {
                TrackKind trackKind = TrackKind.fromId(subKind);
                yield trackKind == null
                        ? Optional.empty()
                        : Optional.of(TrackVariantStore.fileFor(trackKind, id));
            }
            case PORTAL_ROOM -> Optional.of(TrackVariantStore.fileFor(TrackKind.PORTAL_ROOM, id));
        };
    }

    /**
     * One store directory, and the build kind it holds.
     *
     * @param kind    the relay-facing kind
     * @param subKind the part/track kind whose id-space this directory is, or empty
     * @param subSlug the directory under the user-content root — what
     *                {@link UserContentPaths#listBasenamesAcrossSearchDirs} and
     *                {@link UserContentPaths#provenanceOf} are addressed by
     */
    public record Slug(BuilderPhotoPaths.Kind kind, String subKind, String subSlug) {}

    /**
     * Every directory a build can live in.
     *
     * <p>Stated once, here, because two things read it from opposite ends and neither can check the
     * other: a scan walks these directories to find what this install has, and a backup archive's
     * entry names are parsed back through them to find what it used to have. A missing entry is a
     * whole kind of build that silently cannot be recovered.</p>
     *
     * <p>Whole carriages are deliberately absent. A whole-carriage save writes the shell to
     * {@code templates/} as well ({@code BuilderSave.saveWholeCarriage}), and that shell is what the
     * relay holds — listing {@code wholecarriages/} too would offer the same build twice.</p>
     */
    public static List<Slug> slugs() {
        List<Slug> out = new ArrayList<>();
        out.add(new Slug(BuilderPhotoPaths.Kind.CARRIAGE, "", CarriageTemplateStore.SUBDIR));
        out.add(new Slug(BuilderPhotoPaths.Kind.CARRIAGE_GROUP, "", CarriageGroupTemplateStore.SUBDIR));
        out.add(new Slug(BuilderPhotoPaths.Kind.CONTENTS, "", CarriageContentsStore.SUBDIR));
        for (CarriagePartKind part : CarriagePartKind.values()) {
            out.add(new Slug(BuilderPhotoPaths.Kind.PART, part.id(),
                    CarriagePartTemplateStore.SUBDIR_BASE + "/" + part.id()));
        }
        for (TrackKind track : TrackKind.values()) {
            // A portal room is its own relay kind, though it is a track directory on disk.
            BuilderPhotoPaths.Kind kind = track == TrackKind.PORTAL_ROOM
                    ? BuilderPhotoPaths.Kind.PORTAL_ROOM
                    : BuilderPhotoPaths.Kind.TRACK;
            out.add(new Slug(kind, track.id(), track.subdir()));
        }
        return List.copyOf(out);
    }

    /**
     * Read a build's template: from disk if it is there, else out of the newest backup archive that
     * still has it.
     *
     * <p>{@code searchBackups} is the player's answer to the second tier, not a fallback this decides
     * on its own — see the class note. Never throws: an unreadable template is empty, and the build is
     * reported as one that could not be recovered rather than taking the run down.</p>
     */
    public static Optional<Found> read(BuilderPhotoPaths.Kind kind, String subKind, String id,
                                       boolean searchBackups) {
        Optional<Path> file = fileFor(kind, subKind, id);
        if (file.isEmpty()) return Optional.empty();

        Path path = file.get();
        if (Files.isRegularFile(path)) {
            Optional<CompoundTag> tag = readCompressed(path);
            if (tag.isPresent()) return Optional.of(new Found(tag.get(), Origin.LIVE, null));
        }
        return searchBackups ? fromBackups(path) : Optional.empty();
    }

    /** Whether a build's template is on disk right now — what splits the two tiers. */
    public static boolean liveOnDisk(BuilderPhotoPaths.Kind kind, String subKind, String id) {
        return fileFor(kind, subKind, id).filter(Files::isRegularFile).isPresent();
    }

    /**
     * The same template, out of the newest archive that carries it.
     *
     * <p>Archives are searched newest first ({@link PlayerDataBackup#listArchives} already orders
     * them that way), in-instance before the out-of-instance mirror, so a build comes back as the
     * most recent copy of itself that survives anywhere.</p>
     */
    public static Optional<Found> fromBackups(Path file) {
        String entryName = entryNameFor(file);
        if (entryName == null) return Optional.empty();
        for (Path archive : archives()) {
            Optional<CompoundTag> tag = PlayerDataBackup.readEntry(archive, entryName)
                    .flatMap(bytes -> readCompressed(bytes, archive));
            if (tag.isPresent()) return Optional.of(new Found(tag.get(), Origin.BACKUP, archive));
        }
        return Optional.empty();
    }

    /**
     * The archive entry a file on disk was backed up as, or null when it is under no backed-up root.
     *
     * <p>Derived from {@link PlayerDataBackupHook#sources()} rather than by rebuilding the
     * {@code "<label>/<path>"} shape here, so the two can never disagree about what a backup
     * contains — a new backed-up root becomes searchable with no change to this file.</p>
     */
    static String entryNameFor(Path file) {
        return entryNameFor(file, PlayerDataBackupHook.sources());
    }

    /**
     * As {@link #entryNameFor(Path)}, against a given set of backed-up roots — the seam the tests
     * use, since resolving the real ones needs a game directory.
     */
    static String entryNameFor(Path file, List<PlayerDataBackup.Source> sources) {
        if (file == null) return null;
        Path absolute = file.toAbsolutePath().normalize();
        for (PlayerDataBackup.Source source : sources) {
            Path root = source.dir().toAbsolutePath().normalize();
            if (!absolute.startsWith(root)) continue;
            Path relative = root.relativize(absolute);
            if (source.excludes(root, absolute)) return null;
            StringBuilder name = new StringBuilder(source.label());
            for (Path part : relative) name.append('/').append(part);
            return name.toString();
        }
        return null;
    }

    /** Every backup archive that could hold a build, newest first, in-instance before the mirror. */
    private static List<Path> archives() {
        List<Path> all = new ArrayList<>(PlayerDataBackup.listArchives(PlayerDataPaths.backupsRoot()));
        PlayerDataPaths.externalBackupsRoot()
                .ifPresent(root -> all.addAll(PlayerDataBackup.listArchives(root)));
        return all;
    }

    private static Optional<CompoundTag> readCompressed(Path path) {
        try {
            return Optional.of(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()));
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Build reconcile: couldn't read template {}: {}", path, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<CompoundTag> readCompressed(byte[] bytes, Path archive) {
        try {
            return Optional.of(NbtIo.readCompressed(new ByteArrayInputStream(bytes),
                    NbtAccounter.unlimitedHeap()));
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Build reconcile: couldn't read template out of {}: {}",
                    archive == null ? "?" : archive.getFileName(), e.toString());
            return Optional.empty();
        }
    }
}
