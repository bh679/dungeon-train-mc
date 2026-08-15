package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriageContentsAllowList;
import games.brennan.dungeontrain.train.CarriagePlacer.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;

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
 * picking these up as custom carriages.</p>
 *
 * <p>Promote behaviour: when {@link #save} or {@link #delete} runs from a gradle checkout (i.e.
 * {@code ./gradlew runClient}, where {@code src/main/resources/} is writable), the change is also
 * mirrored into the source tree for variants that ship with the game — so editor toggles ship in
 * the next build. Custom variants and production installs silently skip the source-tree step.</p>
 *
 * <p><b>A typed face over {@link ContentsAllowStore}</b>, which holds the loading, caching and
 * promote logic this shares with {@link DimensionalCarriageContentsAllowStore}. The typing is the point: a
 * {@link CarriageVariant} and a dimensional carriage name are both strings underneath and must not be
 * interchangeable at a call site.</p>
 */
public final class CarriageVariantContentsAllowStore {

    static final String SUBDIR = "templates";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/templates/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/templates";

    private static final ContentsAllowStore STORE =
        new ContentsAllowStore(SUBDIR, RESOURCE_PREFIX, SOURCE_REL_PATH);

    private CarriageVariantContentsAllowStore() {}

    public static Path directory() {
        return STORE.directory();
    }

    public static Path fileFor(CarriageVariant variant) {
        return STORE.fileFor(variant.id());
    }

    public static Path sourceFileFor(CarriageType type) {
        return STORE.sourceFileFor(type.name().toLowerCase(Locale.ROOT));
    }

    public static boolean sourceTreeAvailable() {
        return STORE.sourceTreeAvailable();
    }

    public static void clearCache() {
        STORE.clearCache();
    }

    public static void invalidate(String id) {
        STORE.invalidate(id);
    }

    /**
     * Returns the per-variant allow-list. {@link Optional#empty()} means "no sidecar exists" —
     * callers should treat that the same as {@link CarriageContentsAllowList#EMPTY} (every content
     * allowed).
     */
    public static Optional<CarriageContentsAllowList> get(CarriageVariant variant) {
        if (variant == null) return Optional.empty();
        return STORE.get(variant.id());
    }

    public static void save(CarriageVariant variant, CarriageContentsAllowList allow) throws IOException {
        STORE.save(variant.id(), allow);
    }

    public static void saveToSource(CarriageType type, CarriageContentsAllowList allow) throws IOException {
        STORE.saveToSource(type.name().toLowerCase(Locale.ROOT), allow);
    }

    public static boolean delete(CarriageVariant variant) throws IOException {
        return STORE.delete(variant.id());
    }

    public static boolean exists(CarriageVariant variant) {
        return STORE.exists(variant.id());
    }
}
