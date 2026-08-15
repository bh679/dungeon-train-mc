package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.portal.DimensionalCarriageSizes;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateStore;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Editor + builder-facing facade over {@link TrackVariantStore} for portal pocket rooms, mirroring
 * {@link TunnelTemplateStore}.
 *
 * <p>Disk layout {@code config/dungeontrain/user/portals/room/<name>.nbt}, bundled classpath
 * {@code /data/dungeontrain/portals/room/}. There is no bundled room today — with nothing on disk
 * {@link #get} returns empty and {@code PortalCarriageBuilder} stamps the built-in geometry, which
 * is what gives the editor a non-empty plot to author the first real room in.</p>
 *
 * <p>Every load also feeds {@link DimensionalCarriageSizes}, because a room's size is the one thing the
 * plot layout needs to know without a {@link ServerLevel} to hand.</p>
 */
public final class DimensionalCarriageTemplateStore {

    private DimensionalCarriageTemplateStore() {}

    public static Path directory() {
        return TrackVariantStore.directory(TrackKind.DIMENSIONAL_CARRIAGE);
    }

    /**
     * The named room's template, or empty when none has been authored.
     *
     * <p>Height and width are validated against {@link TrackKind#dims}; length is not — see
     * {@link TrackKind#freeLengthAxis()}.</p>
     */
    public static synchronized Optional<StructureTemplate> get(ServerLevel level, String name,
                                                               CarriageDims dims) {
        Optional<StructureTemplate> found =
            TrackVariantStore.get(level, TrackKind.DIMENSIONAL_CARRIAGE, name, dims);
        found.ifPresent(t -> DimensionalCarriageSizes.observe(name, t.getSize()));
        return found;
    }

    /** The room's full box as it would be stamped — the authored length, or the built-in one. */
    public static synchronized Vec3i sizeOf(ServerLevel level, String name, CarriageDims dims) {
        return get(level, name, dims)
            .map(StructureTemplate::getSize)
            .orElseGet(() -> games.brennan.dungeontrain.portal.DimensionalCarriageLayout.builtInSize(dims));
    }

    public static synchronized void save(String name, StructureTemplate template) throws IOException {
        TrackVariantStore.save(TrackKind.DIMENSIONAL_CARRIAGE, name, template);
        DimensionalCarriageSizes.settle(name, template.getSize());
    }

    /** Write {@code template} into the source tree for {@code name} (dev mode). */
    public static synchronized void saveToSource(String name, StructureTemplate template)
            throws IOException {
        TrackVariantStore.saveToSource(TrackKind.DIMENSIONAL_CARRIAGE, name, template);
    }

    public static boolean sourceTreeAvailable() {
        return TrackVariantStore.sourceTreeAvailable();
    }

    public static synchronized boolean delete(String name) throws IOException {
        DimensionalCarriageSizes.forget(name);
        return TrackVariantStore.delete(TrackKind.DIMENSIONAL_CARRIAGE, name);
    }

    public static boolean exists(String name) {
        return TrackVariantStore.exists(TrackKind.DIMENSIONAL_CARRIAGE, name);
    }

    /**
     * Adapter exposing room save through the unified {@link TemplateStore} surface. Rooms have no
     * bundled tier, so promote is unavailable — same as tunnels.
     */
    private static final TemplateStore<Template.DimensionalCarriage> ADAPTER = new TemplateStore<>() {
        @Override public TemplateKind kind() { return TemplateKind.DIMENSIONAL_CARRIAGE; }

        @Override
        public SaveResult save(ServerPlayer player, Template.DimensionalCarriage template) throws Exception {
            DimensionalCarriageEditor.SaveResult r = DimensionalCarriageEditor.save(player, template.name());
            return new SaveResult(r.sourceAttempted(), r.sourceWritten(), r.sourceError());
        }

        @Override
        public boolean canPromote(Template.DimensionalCarriage template) { return false; }

        @Override
        public void promote(Template.DimensionalCarriage template) {
            throw new IllegalStateException(
                "Dimensional carriage templates have no bundled tier — '/dt save default' does not apply.");
        }
    };

    public static TemplateStore<Template.DimensionalCarriage> adapter() {
        return ADAPTER;
    }
}
