package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.core.Vec3i;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The part kind's entry points onto {@link VariantSidecarStore}: per-part sidecar of
 * {@code localPos → [VariantState, ...]} candidate lists, rolled by
 * {@link games.brennan.dungeontrain.train.CarriagePartPlacer#placeAt} at spawn time. The document
 * is a {@link VariantDoc}.
 *
 * <p>Storage: {@code config/dungeontrain/user/parts/<kind>/<name>.variants.json} alongside the
 * part NBT. Local coordinates are cropped to the part's footprint {@code kind.dims(worldDims)}.
 * Every write invalidates {@link StageBlockIndex}, which aggregates parts' blocks for the Stages UI.</p>
 */
public final class CarriagePartVariantBlocks {

    public static final int CURRENT_SCHEMA_VERSION = CarriageVariantBlocks.CURRENT_SCHEMA_VERSION;
    public static final int MIN_STATES_PER_ENTRY = CarriageVariantBlocks.MIN_STATES_PER_ENTRY;

    static final String SUBDIR_BASE = "parts";
    static final String EXT = VariantAddress.EXT;
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/parts/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/parts";

    private CarriagePartVariantBlocks() {}

    /** Where part {@code (kind, name)}'s sidecar lives. */
    public record PartAddress(CarriagePartKind kind, String name) implements VariantAddress {
        @Override public String cacheKey() { return "part:" + kind.id() + ":" + name; }
        @Override public String contextId() { return kind.id() + ":" + name; }
        @Override public String userSubdir() { return SUBDIR_BASE + "/" + kind.id(); }
        @Override public String fileName() { return name + EXT; }
        @Override public Path userPath() { return UserContentPaths.dir(SUBDIR_BASE).resolve(kind.id()).resolve(fileName()); }
        @Override public String bundledResource() { return RESOURCE_PREFIX + kind.id() + "/" + fileName(); }
        @Override public Path sourcePath() {
            return CarriageContentsVariantBlocks.sourceTreePath(SOURCE_REL_PATH, kind.id() + "/" + fileName());
        }
        /** Stages link to parts, so the stage block index is stale after any part sidecar write. */
        @Override public void afterWrite() { StageBlockIndex.invalidateAll(); }
    }

    public static VariantAddress address(CarriagePartKind kind, String name) {
        return new PartAddress(kind, name);
    }

    /** Detached empty document. */
    public static VariantDoc empty() {
        return VariantDoc.empty();
    }

    public static Path configPathFor(CarriagePartKind kind, String name) {
        return address(kind, name).userPath();
    }

    public static String bundledResourceFor(CarriagePartKind kind, String name) {
        return address(kind, name).bundledResource();
    }

    /** Load the sidecar for {@code (kind, name)} bounded to {@code partSize} — config dir first, then bundled. */
    public static VariantDoc loadFor(CarriagePartKind kind, String name, Vec3i partSize) {
        return VariantSidecarStore.loadFor(address(kind, name), partSize);
    }

    /** Source-tree path for {@code (kind, name)}; null when the source tree isn't present or writable. */
    public static Path sourcePathFor(CarriagePartKind kind, String name) {
        return address(kind, name).sourcePath();
    }

    public static boolean delete(CarriagePartKind kind, String name) throws IOException {
        return VariantSidecarStore.delete(address(kind, name));
    }

    /** Move-or-carry the sidecar within {@code kind} — {@link VariantSidecarStore#rename}. */
    public static boolean rename(CarriagePartKind kind, String sourceName, String targetName) throws IOException {
        return VariantSidecarStore.rename(address(kind, sourceName), address(kind, targetName));
    }

    public static void invalidate(CarriagePartKind kind, String name) {
        VariantSidecarStore.invalidate(address(kind, name));
    }

    public static void clearCache() {
        VariantSidecarStore.clearCache();
    }
}
