package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriageContentsAllowList;
import games.brennan.dungeontrain.train.CarriagePlacer.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.ShellPool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Three-tier store for {@code <variant-id>.contents-allow.json} sidecars — the per-carriage-variant
 * allow-list of contents ids that may spawn inside the shell.
 *
 * <ol>
 *   <li><b>Config dir</b> — {@code config/dungeontrain/user/templates/<id>.contents-allow.json}.
 *       Per-install override; the editor's {@code carriage-contents} subcommand writes here.</li>
 *   <li><b>Bundled resource</b> — {@code /data/dungeontrain/templates/<id>.contents-allow.json}
 *       on the classpath. Shipped defaults for built-in variants (e.g. the {@code black} carriage's
 *       no-themed-content rule).</li>
 *   <li><b>Absent</b> — no sidecar in either tier. Caller treats as
 *       {@link CarriageContentsAllowList#EMPTY} (all contents allowed).</li>
 * </ol>
 *
 * <p>Lives in the same {@code config/dungeontrain/user/templates/} directory as the carriage NBTs
 * and parts sidecars; the {@code .contents-allow.json} suffix keeps
 * {@link games.brennan.dungeontrain.train.CarriageVariantRegistry}'s {@code *.nbt}-only scan from
 * picking these up as custom carriages. A Half or Group template's sidecar sits in its pool folder
 * ({@link ShellPool#path}).</p>
 *
 * <p>Policies: {@link SourcePolicy#SHIPS_WITH_GAME} (toggles on a variant that ships are mirrored
 * into the source tree; custom variants and production installs skip it) and
 * {@link DeleteCachePolicy#REMOVE}. {@link #exists} asks the active package only. The typing is the
 * point of this facade: a {@link CarriageVariant} and a portal room name
 * ({@link PortalRoomContentsAllowStore}) are both strings underneath and must not be interchangeable
 * at a call site.</p>
 */
public final class CarriageVariantContentsAllowStore {

    static final String SUBDIR = "templates";
    /** The suffix. Chosen so a {@code *.nbt}-only template scan cannot pick these up as templates. */
    static final String EXT = ".contents-allow.json";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/templates/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/templates";

    private static final ThreeTierStore<CarriageVariant, CarriageContentsAllowList> STORE =
        StoreDescriptor.<CarriageVariant, CarriageContentsAllowList>named("CarriageVariantContentsAllowStore")
            .id(CarriageVariant::id)
            .subdir(SUBDIR)
            .fileName(v -> ShellPool.path(v.id()) + EXT)
            .bundledUnder(RESOURCE_PREFIX)
            .sourceRelDir(SOURCE_REL_PATH)
            .shipMarker(v -> ShellPool.path(v.id()) + ".nbt")
            .codec(StoreCodecs.json(CarriageContentsAllowList::fromJson, CarriageContentsAllowList::toJson))
            .logNoun("contents allow-list")
            .sourcePolicy(SourcePolicy.SHIPS_WITH_GAME)
            .deleteCachePolicy(DeleteCachePolicy.REMOVE)
            .build();

    private CarriageVariantContentsAllowStore() {}

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

    /**
     * Drop {@code id}'s cache entry. Lower-cases the key where {@link #get} and {@link #save} do
     * not — carried over as it was: every id in play is already lower-case, so the asymmetry is
     * inert, and "fixing" it would be a behaviour change riding inside a refactor.
     */
    public static void invalidate(String id) {
        STORE.invalidateId(id.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the per-variant allow-list. {@link Optional#empty()} means "no sidecar exists" —
     * callers should treat that the same as {@link CarriageContentsAllowList#EMPTY} (every content
     * allowed).
     */
    public static Optional<CarriageContentsAllowList> get(CarriageVariant variant) {
        return STORE.get(variant);
    }

    /**
     * Persist the allow-list. An empty allow-list is still written as a file — a present-but-empty
     * record is meaningfully different from no file at all once a player has explicitly toggled
     * everything back on, and it keeps the write idempotent with the toggle round-trip.
     */
    public static void save(CarriageVariant variant, CarriageContentsAllowList allow) throws IOException {
        STORE.save(variant, allow);
    }

    public static void saveToSource(CarriageType type, CarriageContentsAllowList allow) throws IOException {
        STORE.saveToSource(CarriageVariant.of(type), allow);
    }

    public static boolean delete(CarriageVariant variant) throws IOException {
        return STORE.delete(variant);
    }

    public static boolean exists(CarriageVariant variant) {
        return STORE.existsInActive(variant);
    }
}
