package games.brennan.dungeontrain.track.variant;

import games.brennan.dungeontrain.editor.CarriageContentsVariantBlocks;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.MirrorDefaults;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.editor.VariantAddress;
import games.brennan.dungeontrain.editor.VariantDoc;
import games.brennan.dungeontrain.editor.VariantPick;
import games.brennan.dungeontrain.editor.VariantSidecarStore;
import net.minecraft.core.Vec3i;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The track-side kinds' entry points onto {@link VariantSidecarStore}: per-{@code (TrackKind, name)}
 * sidecar of {@code localPos → [VariantState, ...]} candidate lists, enabling shift-right-click
 * variant authoring inside track-side editor plots and deterministic per-tile randomisation at
 * stamp time. The document is a {@link VariantDoc}.
 *
 * <p>Storage: {@code config/dungeontrain/user/<kind.subdir>/<name>.variants.json} alongside the
 * template NBT; writes land in the active package's folder (see
 * {@link TrackVariantStore#directory}). The absent-{@code mirror} default is kind-aware
 * ({@link #defaultMirror}): tunnels X+Z on, every other kind all-off.</p>
 *
 * <p>Detached documents with no track template behind them — the Train Builder's per-world
 * sidecar, a Workbench build, a chunk frame — come from {@link #fromJsonText} with a null kind.</p>
 */
public final class TrackVariantBlocks {

    public static final int CURRENT_SCHEMA_VERSION = CarriageVariantBlocks.CURRENT_SCHEMA_VERSION;
    public static final int MIN_STATES_PER_ENTRY = CarriageVariantBlocks.MIN_STATES_PER_ENTRY;

    private TrackVariantBlocks() {}

    /** Where track {@code (kind, name)}'s sidecar lives. */
    public record TrackAddress(TrackKind kind, String name) implements VariantAddress {
        @Override public String cacheKey() { return "track:" + kind.id() + ":" + name; }
        @Override public String contextId() { return kind.id() + ":" + name; }
        @Override public String userSubdir() { return kind.subdir(); }
        @Override public String fileName() { return name + TrackKind.VARIANTS_EXT; }
        @Override public Path userPath() { return UserContentPaths.activeSubDir(kind.subdir()).resolve(fileName()); }
        @Override public String bundledResource() { return kind.bundledResourcePrefix() + fileName(); }
        @Override public Path sourcePath() {
            return CarriageContentsVariantBlocks.sourceTreePath(kind.sourceRelativePath(), fileName());
        }
        @Override public MirrorDefaults defaultMirror() { return TrackVariantBlocks.defaultMirror(kind); }
    }

    public static VariantAddress address(TrackKind kind, String name) {
        return new TrackAddress(kind, name);
    }

    /**
     * Default mirror axes for a track kind: tunnels X+Z on, every other kind all-off; Y always off.
     * Null kind (a detached document) → all-off.
     */
    public static MirrorDefaults defaultMirror(@Nullable TrackKind kind) {
        boolean tunnel = kind == TrackKind.TUNNEL_SECTION || kind == TrackKind.TUNNEL_PORTAL;
        return tunnel ? MirrorDefaults.TUNNEL : MirrorDefaults.NONE;
    }

    /** Bare empty document, all axes off — only for paths with no kind context. */
    public static VariantDoc empty() {
        return VariantDoc.empty();
    }

    /** Empty document carrying {@code kind}'s default mirror axes — the missing-sidecar state. */
    public static VariantDoc emptyFor(@Nullable TrackKind kind) {
        return VariantDoc.emptyWith(defaultMirror(kind), VariantPick.PLAIN, kind == null ? null : kind.id());
    }

    /** {@link VariantDoc#copyOf}. */
    public static VariantDoc copyOf(VariantDoc source) {
        return VariantDoc.copyOf(source);
    }

    /** Write target for the sidecar — the active package's folder, where a load looks first. */
    public static Path configPathFor(TrackKind kind, String name) {
        return address(kind, name).userPath();
    }

    public static String bundledResourceFor(TrackKind kind, String name) {
        return address(kind, name).bundledResource();
    }

    /**
     * Load the sidecar for {@code (kind, name)} — config first, then bundled — bounded to
     * {@code expectedSize}. The cache holds the whole sidecar; the crop is a per-caller view.
     */
    public static VariantDoc loadFor(TrackKind kind, String name, Vec3i expectedSize) {
        return VariantSidecarStore.loadFor(address(kind, name), expectedSize);
    }

    /**
     * Parse a detached document from text produced by {@link VariantDoc#asJsonText}, bounded to
     * {@code size}. Never enters the cache.
     */
    public static VariantDoc fromJsonText(@Nullable String json, @Nullable TrackKind kind, String name, @Nullable Vec3i size) {
        String contextId = (kind == null ? "builder" : kind.id()) + ":" + name;
        return VariantDoc.fromJsonText(json, defaultMirror(kind), contextId, size);
    }

    /** Source-tree path for {@code (kind, name)}; null when the source tree isn't present or writable. */
    public static Path sourcePathFor(TrackKind kind, String name) {
        return address(kind, name).sourcePath();
    }

    public static boolean delete(TrackKind kind, String name) throws IOException {
        return VariantSidecarStore.delete(address(kind, name));
    }

    public static void invalidate(TrackKind kind, String name) {
        VariantSidecarStore.invalidate(address(kind, name));
    }

    public static void clearCache() {
        VariantSidecarStore.clearCache();
    }
}
