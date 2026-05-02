package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Editor + generator-facing facade over {@link TrackVariantStore} for tunnel
 * templates. Mirrors {@link TrackTemplateStore} / {@link PillarTemplateStore}:
 * single-arg APIs ({@code get(level, variant)}, {@code save(variant, ...)})
 * keep authoring the synthetic "default" name so {@link TunnelEditor} doesn't
 * need to know about variants, while
 * {@link #getFor(ServerLevel, TunnelVariant, String)} feeds
 * {@link games.brennan.dungeontrain.tunnel.TunnelGenerator} when it picks a
 * registry-weighted name per tunnel index.
 *
 * <p>Legacy {@code config/dungeontrain/tunnels/section.nbt} →
 * {@code tunnels/section/default.nbt} migration (and {@code portal}) lives
 * in {@link TrackVariantStore#migrateLegacyPaths()}, called from the variant
 * registry's server-start hook before this store ever sees a request.</p>
 */
public final class TunnelTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TunnelTemplateStore() {}

    public static Path directory() {
        return TrackVariantStore.directory(TrackKind.TUNNEL_SECTION).getParent();
    }

    /** Path to the synthetic-default NBT for {@code variant}. */
    public static Path fileFor(TunnelVariant variant) {
        return TrackVariantStore.fileFor(tunnelKind(variant), TrackKind.DEFAULT_NAME);
    }

    public static synchronized void reload() {
        // Cache lives in TrackVariantStore; nothing to reload here.
    }

    /** Editor-facing — load the StructureTemplate for "default". */
    public static synchronized Optional<StructureTemplate> get(ServerLevel level, TunnelVariant variant) {
        return getFor(level, variant, TrackKind.DEFAULT_NAME);
    }

    /**
     * Generator-facing — load the named tunnel variant's StructureTemplate.
     * Tunnel dims are fixed (don't depend on world dims), so the
     * {@link CarriageDims} passed to {@link TrackVariantStore#get} is a
     * dummy — the kind ignores it for tunnel kinds.
     */
    public static synchronized Optional<StructureTemplate> getFor(
        ServerLevel level, TunnelVariant variant, String name
    ) {
        CarriageDims dims = CarriageDims.clamp(
            CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH, CarriageDims.MIN_HEIGHT);
        return TrackVariantStore.get(level, tunnelKind(variant), name, dims);
    }

    public static synchronized void save(TunnelVariant variant, StructureTemplate template) throws IOException {
        TrackVariantStore.save(tunnelKind(variant), TrackKind.DEFAULT_NAME, template);
    }

    /** Write {@code template} into the source tree for {@code (variant, name)} (dev mode). */
    public static synchronized void saveToSource(
        TunnelVariant variant, String name, StructureTemplate template
    ) throws IOException {
        TrackVariantStore.saveToSource(tunnelKind(variant), name, template);
    }

    /** Promote the runtime config-dir copy of {@code (variant, name)} to the source tree (dev mode). */
    public static synchronized void promote(TunnelVariant variant, String name) throws IOException {
        TrackVariantStore.promote(tunnelKind(variant), name);
    }

    public static boolean sourceTreeAvailable() {
        return TrackVariantStore.sourceTreeAvailable();
    }

    public static synchronized boolean delete(TunnelVariant variant) throws IOException {
        return TrackVariantStore.delete(tunnelKind(variant), TrackKind.DEFAULT_NAME);
    }

    public static boolean exists(TunnelVariant variant) {
        return TrackVariantStore.exists(tunnelKind(variant), TrackKind.DEFAULT_NAME);
    }

    /** Map a {@link TunnelVariant} to its {@link TrackKind}. */
    public static TrackKind tunnelKind(TunnelVariant variant) {
        return switch (variant) {
            case SECTION -> TrackKind.TUNNEL_SECTION;
            case PORTAL -> TrackKind.TUNNEL_PORTAL;
        };
    }
}
