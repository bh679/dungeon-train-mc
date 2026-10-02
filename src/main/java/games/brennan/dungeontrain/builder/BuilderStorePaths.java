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

    /**
     * The most parked carriages a build can have a working copy for — what {@code reset} sweeps.
     * Generous on purpose: it bounds a cleanup loop, not what a mode may park.
     */
    public static final int MAX_VOLUMES = 8;

    /** This build's block-variant sidecar. The file need not exist — an absent one reads as empty. */
    public static Path variantsFile(ServerLevel level) {
        return variantsFile(level, 0);
    }

    /**
     * The block-variant sidecar of parked carriage {@code volume}.
     *
     * <p>One per carriage because each carriage's cells are relative to its own corner: a run saved
     * as a carriage group would otherwise file the same cell of every carriage under one key. The
     * first keeps the original filename, so a world made before groups carried sidecars reads as it
     * always did.</p>
     */
    public static Path variantsFile(ServerLevel level, int volume) {
        return dir(level).resolve(volumeName(VARIANTS_FILE, volume));
    }

    /** This build's container-contents store. The file need not exist — an absent one reads as empty. */
    public static Path contentsFile(ServerLevel level) {
        return contentsFile(level, 0);
    }

    /** The container-contents store of parked carriage {@code volume} — see {@link #variantsFile(ServerLevel, int)}. */
    public static Path contentsFile(ServerLevel level, int volume) {
        return dir(level).resolve(volumeName(CONTENTS_FILE, volume));
    }

    /** {@code build.variants.json} for the first carriage, {@code build.2.variants.json} for the third. */
    static String volumeName(String file, int volume) {
        if (volume <= 0) return file;
        int dot = file.indexOf('.');
        return file.substring(0, dot) + "." + volume + file.substring(dot);
    }

    private static Path dir(ServerLevel level) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        ResourceLocation dim = level.dimension().location();
        String dimSeg = dim.getNamespace() + "__" + dim.getPath();
        return worldRoot.resolve(SUBDIR).resolve(dimSeg);
    }
}
