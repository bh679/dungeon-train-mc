package games.brennan.dungeontrain.editor;

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
 * Editor-facing facade over {@link TrackVariantStore} for prefabs, mirroring
 * {@link PortalRoomTemplateStore}.
 *
 * <p>Disk layout {@code dungeontrain/user/prefabs/designs/<name>.nbt}, bundled classpath
 * {@code /data/dungeontrain/prefabs/designs/}. A prefab is any size
 * ({@link TrackKind#freeSize()}), so every load also feeds {@link PrefabSizes} — the one thing the
 * plot layout needs to know without a {@link ServerLevel} to hand.</p>
 */
public final class PrefabTemplateStore {

    private PrefabTemplateStore() {}

    public static Path directory() {
        return TrackVariantStore.directory(TrackKind.PREFAB);
    }

    /** The named prefab's template, user tier first then bundled, or empty when none exists. */
    public static synchronized Optional<StructureTemplate> get(ServerLevel level, String name,
                                                               CarriageDims dims) {
        Optional<StructureTemplate> found = TrackVariantStore.get(level, TrackKind.PREFAB, name, dims);
        found.ifPresent(t -> PrefabSizes.observe(name, t.getSize()));
        return found;
    }

    /** The bundled (classpath) copy only — what {@code /dt reset} restores. */
    public static Optional<StructureTemplate> getBundled(ServerLevel level, String name, CarriageDims dims) {
        return TrackVariantStore.getBundled(level, TrackKind.PREFAB, name, dims);
    }

    /** The prefab's box as it would be stamped — the saved size, or the default for a new one. */
    public static synchronized Vec3i sizeOf(ServerLevel level, String name, CarriageDims dims) {
        return get(level, name, dims).map(StructureTemplate::getSize).orElseGet(() -> PrefabSizes.sizeOf(name));
    }

    public static synchronized void save(String name, StructureTemplate template) throws IOException {
        TrackVariantStore.save(TrackKind.PREFAB, name, template);
        PrefabSizes.settle(name, template.getSize());
        // A ghost of this prefab standing in some other plot has to follow the new design.
        PrefabAnchorIndex.bump();
    }

    /** Write {@code template} into the source tree for {@code name} (dev mode). */
    public static synchronized void saveToSource(String name, StructureTemplate template) throws IOException {
        TrackVariantStore.saveToSource(TrackKind.PREFAB, name, template);
    }

    public static boolean sourceTreeAvailable() {
        return TrackVariantStore.sourceTreeAvailable();
    }

    public static synchronized boolean delete(String name) throws IOException {
        PrefabSizes.forget(name);
        PrefabAnchorIndex.bump();
        return TrackVariantStore.delete(TrackKind.PREFAB, name);
    }

    public static boolean exists(String name) {
        return TrackVariantStore.exists(TrackKind.PREFAB, name);
    }

    /** True when the user tier or the bundled tier has a template for {@code name}. */
    public static boolean available(String name) {
        return exists(name) || TrackVariantStore.bundled(TrackKind.PREFAB, name);
    }

    private static final TemplateStore<Template.Prefab> ADAPTER = new TemplateStore<>() {
        @Override public TemplateKind kind() { return TemplateKind.PREFAB; }

        @Override
        public SaveResult save(ServerPlayer player, Template.Prefab template) throws Exception {
            PrefabEditor.SaveResult r = PrefabEditor.save(player, template.name());
            return new SaveResult(r.sourceAttempted(), r.sourceWritten(), r.sourceError());
        }

        @Override
        public boolean canPromote(Template.Prefab template) { return sourceTreeAvailable(); }

        @Override
        public void promote(Template.Prefab template) throws Exception {
            TrackVariantStore.promote(TrackKind.PREFAB, template.name());
        }
    };

    public static TemplateStore<Template.Prefab> adapter() {
        return ADAPTER;
    }
}
