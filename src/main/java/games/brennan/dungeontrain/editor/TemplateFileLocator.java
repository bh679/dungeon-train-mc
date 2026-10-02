package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameStore;
import games.brennan.dungeontrain.track.variant.TrackKind;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Where on disk a template in the editor's roster keeps its {@code .nbt} — the folder the editor
 * screen's Open-files button shows the player.
 *
 * <p>Addressed by the roster's own {@code (category, modelId, modelName)} triple, so the client can
 * ask without a {@link games.brennan.dungeontrain.template.Template} (which needs the server's
 * registries to build). Each kind's folder comes from its store's own constant, so a store that
 * moves its files moves this with it.</p>
 */
public final class TemplateFileLocator {

    private static final String NBT = ".nbt";

    /** The folder under a package root, and the template's file name inside it. */
    public record Location(String subdir, String basename) {}

    private TemplateFileLocator() {}

    /**
     * The template's file, relative to a package root; empty for a category with no files
     * (Architecture) or a key that names nothing (blank id, unknown track kind).
     *
     * @param modelId   the roster's command token — the id, or the kind for parts and track-side kinds
     * @param modelName the variant name for parts, track-side kinds, rooms and frames
     */
    public static Optional<Location> of(PlotCategory category, String modelId, String modelName) {
        if (category == null) return Optional.empty();
        String id = modelId == null ? "" : modelId;
        String name = modelName == null ? "" : modelName;
        return switch (category) {
            case CARRIAGES -> named(CarriageTemplateStore.SUBDIR
                + (games.brennan.dungeontrain.train.ShellPool.poolOf(id).folder().isEmpty() ? "" : "/" + games.brennan.dungeontrain.train.ShellPool.poolOf(id).folder()), id);
            case CONTENTS -> named(CarriageContentsStore.SUBDIR, id);
            case WHOLE -> named(WholeCarriageTemplateStore.SUBDIR, id);
            case WHOLE_GROUP -> named(CarriageGroupTemplateStore.SUBDIR, id);
            case PARTS -> id.isEmpty() ? Optional.empty()
                : named(CarriagePartTemplateStore.SUBDIR_BASE + "/" + id, name);
            case TRACKS -> Optional.ofNullable(trackKind(id)).flatMap(k -> named(k.subdir(), name));
            case PORTALS -> named(TrackKind.PORTAL_ROOM.subdir(), name);
            case CHUNK_FRAMES -> named(ChunkFrameStore.SUBDIR, name);
            case BUILDINGS -> named(games.brennan.dungeontrain.building.Buildings.SUBDIR, name);
            // Official buildings have no file of the player's — Big Lost City's live only in its jar.
            case LOST_CITY, ARCHITECTURE -> Optional.empty();
        };
    }

    /**
     * The folder to open for {@code location}: the one holding the file the game actually loads
     * (the active package, or whichever enabled package supplies it), else the active package's
     * folder for the kind — where a first save would put it.
     */
    public static Path folderFor(Location location) {
        Path existing = UserContentPaths.findFile(location.subdir(), location.basename());
        return existing != null ? existing.getParent() : UserContentPaths.dir(location.subdir());
    }

    /**
     * The track-side kind a roster {@code modelId} names. The roster spells the tile {@code track}
     * as well as {@code tile}; pillars, adjuncts and tunnels already use the kind's own id.
     */
    static TrackKind trackKind(String modelId) {
        return "track".equals(modelId) ? TrackKind.TILE : TrackKind.fromId(modelId);
    }

    private static Optional<Location> named(String subdir, String name) {
        return name.isEmpty() ? Optional.empty() : Optional.of(new Location(subdir, name + NBT));
    }
}
