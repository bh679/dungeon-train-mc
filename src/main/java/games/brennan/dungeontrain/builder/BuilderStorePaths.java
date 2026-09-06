package games.brennan.dungeontrain.builder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

/**
 * Where a Train Builder world keeps the authoring documents belonging to its one build — the
 * block-variant sidecar behind the Z menu and the container-contents store behind the C menu.
 *
 * <p><b>Inside the world save, not the config dir.</b> Every other store of these two formats is
 * keyed by the template it belongs to and lives under {@code config/dungeontrain}. A builder draft
 * has no template yet — {@link BuilderCarriagePlot#key()} is the constant {@code builder:carriage}
 * for every builder world — so a config-dir file would be one document that every builder world
 * wrote over the top of. Per-world makes it unique per save, and deletes it with the world.</p>
 *
 * <p>Layout follows {@link games.brennan.dungeontrain.train.CarriagePersistenceStore}, including
 * the {@code namespace__path} dimension segment (colons are reserved on Windows). A builder world
 * only ever holds one build in one dimension; the segment is there so the layout reads the same as
 * every other per-world store rather than because it disambiguates anything today.</p>
 */
public final class BuilderStorePaths {

    private static final String SUBDIR = "dungeontrain/builder";

    /** The block-variant sidecar — schema-compatible with a template's {@code .variants.json}. */
    private static final String VARIANTS_FILE = "build.variants.json";

    /** The container-contents store — schema-compatible with a template's {@code .contents.json}. */
    private static final String CONTENTS_FILE = "build.contents.json";

    private BuilderStorePaths() {}

    /** This build's block-variant sidecar. The file need not exist — an absent one reads as empty. */
    public static Path variantsFile(ServerLevel level) {
        return dir(level).resolve(VARIANTS_FILE);
    }

    /** This build's container-contents store. The file need not exist — an absent one reads as empty. */
    public static Path contentsFile(ServerLevel level) {
        return dir(level).resolve(CONTENTS_FILE);
    }

    private static Path dir(ServerLevel level) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        ResourceLocation dim = level.dimension().location();
        String dimSeg = dim.getNamespace() + "__" + dim.getPath();
        return worldRoot.resolve(SUBDIR).resolve(dimSeg);
    }
}
