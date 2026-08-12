package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageContentsAllowList;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Three-tier store for {@code portals/room/<name>.contents-allow.json} — which contents templates a
 * portal room may draw when its Contents setting
 * ({@link games.brennan.dungeontrain.portal.PortalRoomContents}) is anything but Off.
 *
 * <p>The room-side counterpart of {@link CarriageVariantContentsAllowStore}, sharing its whole
 * implementation through {@link ContentsAllowStore}. Sits beside the room's other per-name files —
 * {@code <name>.nbt}, {@code <name>.variants.json}, {@code weights.json} — so everything about a
 * room is in one directory.</p>
 *
 * <h2>Absent means everything</h2>
 * <p>No sidecar is the default and reads as {@link CarriageContentsAllowList#EMPTY}: every
 * registered template is eligible. That is what a furnished room did before this store existed, so
 * adding it changes nothing until an author actually excludes something.</p>
 *
 * <p><b>Excluding everything is not the same as Off.</b> An all-excluded list means the author
 * turned each template down one at a time and expects an empty room; {@code PortalCarriageBuilder}
 * checks for that case and places nothing, rather than letting
 * {@code CarriageContentsRegistry.pick}'s never-leave-a-carriage-unfurnished fallback drop the
 * built-in default into a room nobody asked to furnish.</p>
 */
public final class PortalRoomContentsAllowStore {

    /**
     * All three paths come from {@link TrackKind#PORTAL_ROOM} rather than being spelled out, so the
     * sidecar always lands beside the room's {@code .nbt} — if a kind's subdir ever moves, this
     * moves with it instead of quietly writing to a directory nothing reads.
     */
    private static final ContentsAllowStore STORE = new ContentsAllowStore(
        TrackKind.PORTAL_ROOM.subdir(),
        TrackKind.PORTAL_ROOM.bundledResourcePrefix(),
        TrackKind.PORTAL_ROOM.sourceRelativePath());

    private PortalRoomContentsAllowStore() {}

    public static Path directory() {
        return STORE.directory();
    }

    public static Path fileFor(String roomName) {
        return STORE.fileFor(roomName);
    }

    public static boolean sourceTreeAvailable() {
        return STORE.sourceTreeAvailable();
    }

    public static void clearCache() {
        STORE.clearCache();
    }

    public static void invalidate(String roomName) {
        STORE.invalidate(roomName);
    }

    /**
     * The room's allow-list. {@link Optional#empty()} means "no sidecar" — treat as
     * {@link CarriageContentsAllowList#EMPTY}.
     */
    public static Optional<CarriageContentsAllowList> get(String roomName) {
        if (roomName == null || roomName.isEmpty()) return Optional.empty();
        return STORE.get(roomName);
    }

    /** The room's allow-list, or {@link CarriageContentsAllowList#EMPTY} when it has none. */
    public static CarriageContentsAllowList getOrEmpty(String roomName) {
        return get(roomName).orElse(CarriageContentsAllowList.EMPTY);
    }

    public static void save(String roomName, CarriageContentsAllowList allow) throws IOException {
        STORE.save(roomName, allow);
    }

    public static void saveToSource(String roomName, CarriageContentsAllowList allow) throws IOException {
        STORE.saveToSource(roomName, allow);
    }

    public static boolean delete(String roomName) throws IOException {
        return STORE.delete(roomName);
    }

    public static boolean exists(String roomName) {
        return STORE.exists(roomName);
    }
}
