package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.WholeKind;
import net.minecraft.core.Vec3i;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The Whole section's entry points onto {@link VariantSidecarStore}: one sidecar per room
 * ({@link WholeKind#ROOM}) or group ({@link WholeKind#GROUP}), what the Z menu writes inside a whole
 * plot and what {@code WholeOverlay} rolls at spawn time after the verbatim stamp. The document is
 * a {@link VariantDoc}.
 *
 * <p>Storage: {@code config/dungeontrain/user/<kind subdir>/<id>.variants.json} beside the template
 * NBT, bundled at {@code /data/dungeontrain/whole/<kind>/<id>.variants.json}. Local coordinates are
 * plot-local from the room's (or the run's) shell corner.</p>
 */
public final class WholeVariantBlocks {

    public static final int CURRENT_SCHEMA_VERSION = CarriageVariantBlocks.CURRENT_SCHEMA_VERSION;
    public static final int MIN_STATES_PER_ENTRY = CarriageVariantBlocks.MIN_STATES_PER_ENTRY;

    public static final String EXT = VariantAddress.EXT;

    private WholeVariantBlocks() {}

    /** Where whole {@code (kind, id)}'s sidecar lives. */
    public record WholeAddress(WholeKind kind, String id) implements VariantAddress {
        @Override public String cacheKey() { return "whole:" + kind.id() + ":" + id; }
        @Override public String contextId() { return kind.id() + ":" + id; }
        @Override public String userSubdir() { return kind.userSubdir(); }
        @Override public String fileName() { return id + EXT; }
        @Override public Path userPath() { return UserContentPaths.dir(kind.userSubdir()).resolve(fileName()); }
        @Override public String bundledResource() { return kind.bundledResourcePrefix() + fileName(); }
        @Override public Path sourcePath() {
            return CarriageContentsVariantBlocks.sourceTreePath("src/main/resources", bundledResource().substring(1));
        }
    }

    public static VariantAddress address(WholeKind kind, String id) {
        return new WholeAddress(kind, id);
    }

    /** Detached empty document. */
    public static VariantDoc empty() {
        return VariantDoc.empty();
    }

    public static Path configPathFor(WholeKind kind, String id) {
        return address(kind, id).userPath();
    }

    /** Load the sidecar for {@code (kind, id)} bounded to {@code footprint} — config dir first, then bundled. */
    public static VariantDoc loadFor(WholeKind kind, String id, Vec3i footprint) {
        return VariantSidecarStore.loadFor(address(kind, id), footprint);
    }

    public static boolean delete(WholeKind kind, String id) throws IOException {
        return VariantSidecarStore.delete(address(kind, id));
    }

    /** Move-or-carry the sidecar within {@code kind} — {@link VariantSidecarStore#rename}. */
    public static boolean rename(WholeKind kind, String sourceId, String targetId) throws IOException {
        return VariantSidecarStore.rename(address(kind, sourceId), address(kind, targetId));
    }

    public static void invalidate(WholeKind kind, String id) {
        VariantSidecarStore.invalidate(address(kind, id));
    }

    public static void clearCache() {
        VariantSidecarStore.clearCache();
    }

    /** Source-tree path of the bundled sidecar; null outside a writable dev checkout. */
    public static Path sourcePathFor(WholeKind kind, String id) {
        return address(kind, id).sourcePath();
    }
}
