package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriageContents;
import net.minecraft.core.Vec3i;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The contents kind's entry points onto {@link VariantSidecarStore}: per-contents sidecar of
 * {@code localPos → [VariantState, ...]} candidate lists, the interior-layout analogue of
 * {@link CarriageVariantBlocks}, rolled by {@link games.brennan.dungeontrain.train.CarriageContentsPlacer}
 * at spawn time. The document is a {@link VariantDoc}; this kind rolls with {@link VariantPick#LIVE_TV}.
 *
 * <p>Storage: {@code config/dungeontrain/user/contents/<id>.variants.json} alongside the contents
 * NBT. Local coordinates are <b>interior-local</b> — {@code clicked - (carriageOrigin + (1,1,1))} —
 * and anything outside the interior box is cropped per caller.</p>
 */
public final class CarriageContentsVariantBlocks {

    public static final int CURRENT_SCHEMA_VERSION = CarriageVariantBlocks.CURRENT_SCHEMA_VERSION;
    public static final int MIN_STATES_PER_ENTRY = CarriageVariantBlocks.MIN_STATES_PER_ENTRY;

    static final String SUBDIR = "contents";
    static final String EXT = VariantAddress.EXT;
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/contents/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/contents";

    private CarriageContentsVariantBlocks() {}

    /** Where contents {@code id}'s sidecar lives. */
    public record ContentsAddress(String id) implements VariantAddress {
        @Override public String cacheKey() { return "contents:" + id; }
        @Override public String contextId() { return id; }
        @Override public String userSubdir() { return SUBDIR; }
        @Override public String fileName() { return id + EXT; }
        @Override public Path userPath() { return UserContentPaths.dir(SUBDIR).resolve(fileName()); }
        @Override public String bundledResource() { return RESOURCE_PREFIX + fileName(); }
        @Override public Path sourcePath() { return sourceTreePath(SOURCE_REL_PATH, fileName()); }
        @Override public VariantPick pick() { return VariantPick.LIVE_TV; }
    }

    public static VariantAddress address(CarriageContents contents) {
        return new ContentsAddress(contents.id());
    }

    public static VariantAddress address(String id) {
        return new ContentsAddress(id);
    }

    /** Detached empty document. */
    public static VariantDoc empty() {
        return VariantDoc.emptyWith(MirrorDefaults.NONE, VariantPick.LIVE_TV, null);
    }

    public static Path configPathFor(CarriageContents contents) {
        return configPathForId(contents.id());
    }

    public static Path configPathForId(String id) {
        return address(id).userPath();
    }

    public static String bundledResourceFor(CarriageContents contents) {
        return address(contents).bundledResource();
    }

    /** Load the sidecar for {@code contents} bounded to {@code interiorSize} — config dir first, then bundled. */
    public static VariantDoc loadFor(CarriageContents contents, Vec3i interiorSize) {
        return VariantSidecarStore.loadFor(address(contents), interiorSize);
    }

    /** {@link VariantDoc#inBand}. */
    public static List<VariantState> inBand(List<VariantState> states, int tier) {
        return VariantDoc.inBand(states, tier);
    }

    public static boolean delete(CarriageContents contents) throws IOException {
        return VariantSidecarStore.delete(address(contents));
    }

    /** Move-or-carry the sidecar from {@code sourceId} to {@code targetId} — {@link VariantSidecarStore#rename}. */
    public static boolean rename(String sourceId, String targetId) throws IOException {
        return VariantSidecarStore.rename(address(sourceId), address(targetId));
    }

    public static void invalidate(String id) {
        VariantSidecarStore.invalidate(address(id));
    }

    public static void clearCache() {
        VariantSidecarStore.clearCache();
    }

    public static Path sourcePathFor(CarriageContents contents) {
        return sourcePathForId(contents.id());
    }

    /** Source-tree path under an outgoing name — save-as / rename flows delete the old file by it. Null outside a writable checkout. */
    public static Path sourcePathForId(String id) {
        return address(id).sourcePath();
    }

    /** {@code <project root>/<rel>/<fileName>}, or null when the source tree is absent or read-only. */
    public static Path sourceTreePath(String rel, String fileName) {
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        if (projectRoot == null) return null;
        Path resources = projectRoot.resolve("src/main/resources");
        if (!Files.isDirectory(resources) || !Files.isWritable(resources)) return null;
        return projectRoot.resolve(rel).resolve(fileName);
    }
}
