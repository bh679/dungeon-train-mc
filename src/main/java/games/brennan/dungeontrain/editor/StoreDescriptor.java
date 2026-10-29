package games.brennan.dungeontrain.editor;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Everything that differs between two {@link ThreeTierStore}s, as a builder: where each tier keeps a
 * key's file, how to read and write the value, and the policies the review asked every store to
 * decide out loud — see {@link SourcePolicy} and {@link DeleteCachePolicy}.
 *
 * <p>Required: {@link #id}, {@link #subdir}, {@link #fileName}, {@link #bundledResource},
 * {@link #sourceRelDir}, {@link #codec}, {@link #logNoun}. Everything else has the default most
 * stores want.</p>
 */
public final class StoreDescriptor<K, V> {

    /** What a {@link ThreeTierStore} leaves in its cache after {@code save}. */
    public enum SaveCachePolicy {
        /** Cache the value just written. */
        PUT,
        /** Drop the entry: the file on disk is not byte-for-byte the value (repaired on write), so the next read re-loads it. */
        DROP
    }

    /** The per-install tier's directories — {@link UserContentPaths} in production, temp dirs under test. */
    public interface UserTier {
        /** The active package's write directory for {@code subdir}. */
        Path writeDir(String subdir);

        /** First existing {@code <searchDir>/<fileName>} across the enabled packages, or null. */
        Path findFile(String subdir, String fileName);

        UserTier DEFAULT = new UserTier() {
            @Override public Path writeDir(String subdir) { return UserContentPaths.dir(subdir); }
            @Override public Path findFile(String subdir, String fileName) { return UserContentPaths.findFile(subdir, fileName); }
        };
    }

    final String name;
    Function<K, String> id;
    Function<K, String> subdir;
    Function<K, String> fileName;
    Function<K, String> bundledResource;
    Function<K, String> sourceRelDir;
    Function<K, String> shipMarker;
    StoreCodec<V> codec;
    String logNoun;
    SourcePolicy sourcePolicy = SourcePolicy.NEVER;
    DeleteCachePolicy deleteCachePolicy = DeleteCachePolicy.REMOVE;
    SaveCachePolicy saveCachePolicy = SaveCachePolicy.PUT;
    Consumer<K> onSave = k -> {};
    Consumer<K> onDelete = k -> {};
    BiConsumer<K, K> onRename = (a, b) -> {};
    Runnable onClear = () -> {};
    BooleanSupplier devMode = EditorDevMode::isEnabled;
    UserTier userTier = UserTier.DEFAULT;
    SourceTree.Locator sourceTree = SourceTree.DEFAULT;

    private StoreDescriptor(String name) {
        this.name = name;
    }

    /** Start a descriptor; {@code name} is the {@link StoreRegistry} key, e.g. the facade's class name. */
    public static <K, V> StoreDescriptor<K, V> named(String name) {
        return new StoreDescriptor<>(Objects.requireNonNull(name));
    }

    /** The key's canonical id — the cache key and what log lines print. */
    public StoreDescriptor<K, V> id(Function<K, String> id) { this.id = id; return this; }

    /** The user-tier subdirectory, as {@link UserContentPaths#findFile} takes it. */
    public StoreDescriptor<K, V> subdir(Function<K, String> subdir) { this.subdir = subdir; return this; }

    public StoreDescriptor<K, V> subdir(String subdir) { return subdir(k -> subdir); }

    /** The file's name under that directory, extension included. */
    public StoreDescriptor<K, V> fileName(Function<K, String> fileName) { this.fileName = fileName; return this; }

    /** The absolute classpath path of the bundled copy. */
    public StoreDescriptor<K, V> bundledResource(Function<K, String> bundledResource) { this.bundledResource = bundledResource; return this; }

    /** Convenience: {@code prefix + fileName(key)}. Call after {@link #fileName}. */
    public StoreDescriptor<K, V> bundledUnder(String resourcePrefix) {
        Function<K, String> fn = Objects.requireNonNull(fileName, "fileName first");
        return bundledResource(k -> resourcePrefix + fn.apply(k));
    }

    /** The source-tree directory, relative to the checkout root ({@code src/main/resources/...}). */
    public StoreDescriptor<K, V> sourceRelDir(Function<K, String> sourceRelDir) { this.sourceRelDir = sourceRelDir; return this; }

    public StoreDescriptor<K, V> sourceRelDir(String sourceRelDir) { return sourceRelDir(k -> sourceRelDir); }

    /**
     * For {@link SourcePolicy#SHIPS_WITH_GAME}: the file whose presence in the source directory
     * means this key ships. Default: {@link #fileName} with its extension replaced by {@code .nbt}.
     */
    public StoreDescriptor<K, V> shipMarker(Function<K, String> shipMarker) { this.shipMarker = shipMarker; return this; }

    public StoreDescriptor<K, V> codec(StoreCodec<V> codec) { this.codec = codec; return this; }

    /** "whole carriage", "parts assignment", … — the noun every log line uses. */
    public StoreDescriptor<K, V> logNoun(String logNoun) { this.logNoun = logNoun; return this; }

    public StoreDescriptor<K, V> sourcePolicy(SourcePolicy p) { this.sourcePolicy = p; return this; }

    public StoreDescriptor<K, V> deleteCachePolicy(DeleteCachePolicy p) { this.deleteCachePolicy = p; return this; }

    public StoreDescriptor<K, V> saveCachePolicy(SaveCachePolicy p) { this.saveCachePolicy = p; return this; }

    /** Runs after a config-dir save (after the cache update, before the source write-through). */
    public StoreDescriptor<K, V> onSave(Consumer<K> hook) { this.onSave = hook; return this; }

    /** Runs after a config-dir delete (after the cache update, before the source delete-through). */
    public StoreDescriptor<K, V> onDelete(Consumer<K> hook) { this.onDelete = hook; return this; }

    /** Runs after a rename moved the file and cache entry: {@code (source, target)}. */
    public StoreDescriptor<K, V> onRename(BiConsumer<K, K> hook) { this.onRename = hook; return this; }

    /** Runs after {@code clearCache}. */
    public StoreDescriptor<K, V> onClear(Runnable hook) { this.onClear = hook; return this; }

    /** Save, delete and clear all fire this. */
    public StoreDescriptor<K, V> onAnyChange(Runnable hook) {
        return onSave(k -> hook.run()).onDelete(k -> hook.run()).onClear(hook);
    }

    /** Test seam for {@link SourcePolicy#DEV_MODE}. */
    public StoreDescriptor<K, V> devMode(BooleanSupplier devMode) { this.devMode = devMode; return this; }

    /** Test seam for the per-install tier. */
    public StoreDescriptor<K, V> userTier(UserTier userTier) { this.userTier = userTier; return this; }

    /** Test seam for the source tree. */
    public StoreDescriptor<K, V> sourceTree(SourceTree.Locator sourceTree) { this.sourceTree = sourceTree; return this; }

    public ThreeTierStore<K, V> build() {
        Objects.requireNonNull(id, name + ": id");
        Objects.requireNonNull(subdir, name + ": subdir");
        Objects.requireNonNull(fileName, name + ": fileName");
        Objects.requireNonNull(bundledResource, name + ": bundledResource");
        Objects.requireNonNull(sourceRelDir, name + ": sourceRelDir");
        Objects.requireNonNull(codec, name + ": codec");
        Objects.requireNonNull(logNoun, name + ": logNoun");
        if (shipMarker == null) {
            Function<K, String> fn = fileName;
            shipMarker = k -> {
                String f = fn.apply(k);
                int dot = f.indexOf('.');
                return (dot < 0 ? f : f.substring(0, dot)) + ".nbt";
            };
        }
        return new ThreeTierStore<>(this);
    }
}
