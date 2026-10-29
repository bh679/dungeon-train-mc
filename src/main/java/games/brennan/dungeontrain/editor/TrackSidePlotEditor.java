package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateDecor;
import games.brennan.dungeontrain.template.TemplateStamp;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The plot editor for one track-side {@link TrackKind}: the open-air tile, a pillar section, a
 * stairs adjunct, a tunnel kind. All of them lay out through {@link TrackSidePlots}, list their
 * variants through {@link TrackVariantRegistry}, persist through {@link TrackVariantStore} and carry
 * a {@link TrackVariantBlocks} sidecar — so one class, configured per kind by a {@link Spec},
 * replaces the four static editors that used to spell the same lifecycle out.
 *
 * <p>Entering any plot of a kind restamps the whole row, so every registered variant is visible on
 * arrival. The return point is recorded in {@link EditorSessions}, shared with every other editor,
 * so one {@code /dungeontrain editor exit} restores the player.</p>
 *
 * @param <T> the record for this kind — {@link Template.Track}, {@link Template.Pillar},
 *            {@link Template.Adjunct} or {@link Template.Tunnel}
 */
public final class TrackSidePlotEditor<T extends Template> extends PlotEditor<T> {

    /** Procedural geometry for a variant the store has no template for (the unauthored default). */
    @FunctionalInterface
    public interface Fallback {
        void stamp(ServerLevel level, BlockPos origin, CarriageDims dims);
    }

    /** A kind whose placement is not the plain store stamp — tunnels go through {@code TunnelPlacer}. */
    @FunctionalInterface
    public interface Placer {
        void place(ServerLevel level, BlockPos origin, String name);
    }

    /** A kind whose erase is not the plain {@link EditorPlotBox#erase}. */
    @FunctionalInterface
    public interface Eraser {
        void erase(ServerLevel level, BlockPos origin, Vec3i size);
    }

    /**
     * What tells one track-side kind from another.
     *
     * @param kind           the row this editor owns
     * @param model          the record for a variant name of this kind
     * @param fallback       what to stamp when the store has nothing; null for a kind whose placer
     *                       handles that itself
     * @param placer         placement that bypasses the store stamp, or null for the default
     * @param eraser         an erase that bypasses {@link EditorPlotBox#erase}, or null for the default
     * @param advancement    the {@code editor_action} id a save triggers
     * @param captureVoid    the block a capture treats as "not part of this template"
     * @param worldDimsGate  pass the world's dims to {@link TrackVariantStore#get}; false passes the
     *                       minimum dims, for a kind whose size ignores the world's width
     * @param containerLinks keep a {@link ContainerContentsStore} per variant (the tile does)
     * @param enterNote      a chat line sent on every entry, or null
     */
    public record Spec<T extends Template>(TrackKind kind, Function<String, T> model,
                                           @Nullable Fallback fallback, @Nullable Placer placer,
                                           @Nullable Eraser eraser, String advancement,
                                           Block captureVoid, boolean worldDimsGate,
                                           boolean containerLinks, @Nullable String enterNote) {}

    private final Spec<T> spec;

    public TrackSidePlotEditor(Spec<T> spec) {
        this.spec = spec;
    }

    /** The row this editor owns. */
    public TrackKind kind() {
        return spec.kind();
    }

    /** The record for {@code name} on this row. */
    public T model(String name) {
        return spec.model().apply(name);
    }

    @Override public EditorCategory category() {
        return EditorCategory.TRACKS;
    }

    /** The record's own baseline key — the one the dirty scan reads. */
    @Override public String snapshotKey(T t) {
        return t.snapshotKey().orElseThrow();
    }

    @Override public BlockPos origin(T t, CarriageDims dims) {
        return TrackSidePlots.plotOrigin(spec.kind(), t.variantName(), dims);
    }

    @Override public Vec3i size(T t, CarriageDims dims) {
        return spec.kind().dims(dims);
    }

    @Override public List<T> resident(CarriageDims dims) {
        return TrackVariantRegistry.namesFor(spec.kind()).stream().map(spec.model()).toList();
    }

    @Override protected List<T> stampOnEnter(T t, CarriageDims dims) {
        return resident(dims);
    }

    @Override protected void beforeStamp(T t) {
        // Drop in-session contents-store changes (loot-prefab links) so the re-stamp reads the
        // last-saved disk state; placement writes only touch the in-memory cache until /save.
        if (spec.containerLinks()) ContainerContentsStore.invalidate(contentsKey(t));
    }

    @Override protected void erase(ServerLevel level, BlockPos origin, T t, CarriageDims dims) {
        if (spec.eraser() == null) {
            super.erase(level, origin, t, dims);
        } else {
            spec.eraser().erase(level, origin, size(t, dims));
        }
    }

    @Override protected void place(ServerLevel level, BlockPos origin, T t, CarriageDims dims) {
        String name = t.variantName();
        if (spec.placer() != null) {
            spec.placer().place(level, origin, name);
            return;
        }
        Optional<StructureTemplate> stored = TrackVariantStore.get(level, spec.kind(), name, storeDims(dims));
        if (stored.isPresent()) {
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
            TemplateStamp.placeWithDecor(level, origin, stored.get(), settings);
            return;
        }
        if (spec.fallback() != null) spec.fallback().stamp(level, origin, dims);
    }

    /** The dims the store's size gate sees: the world's, or the minimum for a fixed-size kind. */
    private CarriageDims storeDims(CarriageDims dims) {
        return spec.worldDimsGate() ? dims
            : CarriageDims.clamp(CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH, CarriageDims.MIN_HEIGHT);
    }

    @Override protected Block captureVoid() {
        return spec.captureVoid();
    }

    @Override protected StructureTemplate capture(ServerLevel level, BlockPos origin, T t, CarriageDims dims) {
        return TemplateDecor.capture(level, origin, size(t, dims), captureVoid());
    }

    @Override protected void persist(T t, StructureTemplate template, boolean toSource) throws IOException {
        if (toSource) {
            TrackVariantStore.saveToSource(spec.kind(), t.variantName(), template);
        } else {
            TrackVariantStore.save(spec.kind(), t.variantName(), template);
        }
    }

    @Override protected void sidecars(T t, CarriageDims dims, boolean toSource) throws IOException {
        if (toSource) {
            // The variant pools live in the user tier already (the Z menu writes them eagerly);
            // only the dev-mode promotion is left to the save.
            Vec3i footprint = spec.kind().dims(storeDims(dims));
            TrackVariantBlocks.loadFor(spec.kind(), t.variantName(), footprint)
                .saveToSource();
        } else if (spec.containerLinks()) {
            // Persist any in-session loot-prefab link changes accumulated since enter
            // (PrefabUseHandler defers its writes until /save).
            ContainerContentsStore.loadFor(contentsKey(t)).save();
        }
    }

    private String contentsKey(T t) {
        return BlockVariantPlot.trackKey(spec.kind(), t.variantName());
    }

    @Override protected String advancement(T t) {
        return spec.advancement();
    }

    @Override protected String describe(T t) {
        return spec.kind().id() + "/" + t.variantName();
    }

    @Override protected void afterEnter(ServerPlayer player, T t) {
        if (spec.enterNote() != null) player.sendSystemMessage(Component.literal(spec.enterNote()));
    }
}
