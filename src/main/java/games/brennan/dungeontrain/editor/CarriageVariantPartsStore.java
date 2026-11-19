package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriagePartAssignment;
import games.brennan.dungeontrain.train.CarriagePlacer.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.ShellPool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Three-tier store for {@code <variant-id>.parts.json} sidecars — the
 * per-carriage-variant mapping of {@link games.brennan.dungeontrain.train.CarriagePartKind}
 * → part template name that tells
 * {@link games.brennan.dungeontrain.train.CarriagePlacer#placeAt(net.minecraft.server.level.ServerLevel, net.minecraft.core.BlockPos, CarriageVariant, games.brennan.dungeontrain.train.CarriageDims)}
 * to compose the carriage from parts instead of stamping the monolithic NBT.
 *
 * <ol>
 *   <li><b>Config dir</b> — {@code config/dungeontrain/user/templates/<id>.parts.json}.
 *       Per-install override; the editor's {@code part set/clear} commands write here.</li>
 *   <li><b>Bundled resource</b> — {@code /data/dungeontrain/templates/<id>.parts.json}
 *       on the classpath. Shipped defaults for built-in variants that the mod
 *       wants to render via parts out-of-the-box. Optional — absent means the
 *       variant falls through to the monolithic path.</li>
 *   <li><b>Absent</b> — no sidecar in either tier. Caller renders the
 *       monolithic NBT (existing behaviour).</li>
 * </ol>
 *
 * <p>Lives in the same directory as carriage NBTs because a sidecar always
 * accompanies a specific carriage variant; the {@code .parts.json} suffix keeps
 * {@link games.brennan.dungeontrain.train.CarriageVariantRegistry}'s
 * {@code *.nbt}-only scan from treating sidecars as custom carriages.</p>
 *
 * <p>Policies: {@link SourcePolicy#SHIPS_WITH_GAME} — a save or delete mirrors into the source tree
 * only when the variant's {@code <id>.nbt} is already there (built-ins and shipping customs such as
 * {@code pen}), never for a per-install variant; {@link DeleteCachePolicy#REMOVE}, so clearing a
 * shipped variant's override falls back to the bundled sidecar at once. {@link #exists} asks the
 * active package only.</p>
 */
public final class CarriageVariantPartsStore {

    static final String SUBDIR = "templates";
    static final String EXT = ".parts.json";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/templates/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/templates";

    private static final ThreeTierStore<CarriageVariant, CarriagePartAssignment> STORE =
        StoreDescriptor.<CarriageVariant, CarriagePartAssignment>named("CarriageVariantPartsStore")
            .id(CarriageVariant::id)
            .subdir(SUBDIR)
            .fileName(v -> ShellPool.path(v.id()) + EXT)
            .bundledUnder(RESOURCE_PREFIX)
            .sourceRelDir(SOURCE_REL_PATH)
            .shipMarker(v -> ShellPool.path(v.id()) + ".nbt")
            .codec(StoreCodecs.json(CarriagePartAssignment::fromJson, CarriagePartAssignment::toJson))
            .logNoun("parts assignment")
            .sourcePolicy(SourcePolicy.SHIPS_WITH_GAME)
            .deleteCachePolicy(DeleteCachePolicy.REMOVE)
            .onAnyChange(StageBlockIndex::invalidateAll)
            .build();

    private CarriageVariantPartsStore() {}

    public static Path directory() {
        return UserContentPaths.dir(SUBDIR);
    }

    public static Path fileFor(CarriageVariant variant) {
        return STORE.fileFor(variant);
    }

    public static Path sourceFileFor(CarriageType type) {
        return STORE.sourceFileFor(CarriageVariant.of(type));
    }

    public static boolean sourceTreeAvailable() {
        return STORE.sourceTreeAvailable();
    }

    public static void clearCache() {
        STORE.clearCache();
    }

    /** Lower-cases the id, as it always has; every id in play is already lower-case. */
    public static void invalidate(String id) {
        STORE.invalidateId(id.toLowerCase(Locale.ROOT));
        StageBlockIndex.invalidateAll();
    }

    public static Optional<CarriagePartAssignment> get(CarriageVariant variant) {
        return STORE.get(variant);
    }

    public static void save(CarriageVariant variant, CarriagePartAssignment assignment) throws IOException {
        STORE.save(variant, assignment);
    }

    public static void saveToSource(CarriageType type, CarriagePartAssignment assignment) throws IOException {
        STORE.saveToSource(CarriageVariant.of(type), assignment);
    }

    public static boolean delete(CarriageVariant variant) throws IOException {
        return STORE.delete(variant);
    }

    public static boolean exists(CarriageVariant variant) {
        return STORE.existsInActive(variant);
    }

    public static boolean bundled(CarriageVariant variant) {
        return STORE.bundled(variant);
    }
}
