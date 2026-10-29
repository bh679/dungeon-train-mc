package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageDoorCells;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.IOException;
import java.util.List;

/**
 * The carriage row's plot lifecycle: one plot per registered {@link CarriageVariant}, each sized
 * by its own box ({@link CarriageEditor#plotDims} — longer for the portal corridor). Row layout,
 * creation, duplication, resizing and save-as stay on {@link CarriageEditor}; they call this
 * instance where they used to stamp or clear inline.
 */
public final class CarriagePlotEditor extends PlotEditor<Template.Carriage> {

    private static final CarriagePlotEditor INSTANCE = new CarriagePlotEditor();

    public static CarriagePlotEditor get() {
        return INSTANCE;
    }

    private CarriagePlotEditor() {}

    @Override public EditorCategory category() {
        return EditorCategory.CARRIAGES;
    }

    @Override public String snapshotKey(Template.Carriage t) {
        return t.snapshotKey().orElseThrow();
    }

    @Override public BlockPos origin(Template.Carriage t, CarriageDims dims) {
        return CarriageEditor.plotOrigin(t.variant(), dims);
    }

    /** The plot's own box, not the world's carriage dims — see {@link CarriageEditor#plotDims}. */
    @Override public Vec3i size(Template.Carriage t, CarriageDims dims) {
        CarriageDims box = CarriageEditor.plotDims(t.variant(), dims);
        return new Vec3i(box.length(), box.height(), box.width());
    }

    @Override public List<Template.Carriage> resident(CarriageDims dims) {
        return CarriageVariantRegistry.allVariants().stream().map(Template.Carriage::new).toList();
    }

    @Override protected BlockPos door(BlockPos origin, Template.Carriage t, CarriageDims dims) {
        return EditorPlotArrival.firstOrNull(
            CarriageDoorCells.doorBases(origin, CarriageEditor.plotDims(t.variant(), dims)));
    }

    @Override protected void beforeStamp(Template.Carriage t) {
        // Drop any stale cached sidecar so each stamp picks up manual JSON edits made since the
        // last load, and in-session contents-store changes so the re-stamp reads disk state.
        CarriageVariantBlocks.invalidate(t.variant().id());
        ContainerContentsStore.invalidate(BlockVariantPlot.carriageKey(t.variant().id()));
    }

    @Override protected void erase(ServerLevel level, BlockPos origin, Template.Carriage t, CarriageDims dims) {
        CarriagePlacer.eraseAt(level, origin, CarriageEditor.plotDims(t.variant(), dims));
        EditorPlotCage.setOutline(level, origin, size(t, dims), Blocks.AIR.defaultBlockState());
    }

    /** The placer keeps the world's dims and derives the corridor length itself. */
    @Override protected void place(ServerLevel level, BlockPos origin, Template.Carriage t, CarriageDims dims) {
        CarriagePlacer.placeAt(level, origin, t.variant(), dims);
    }

    @Override protected StructureTemplate capture(ServerLevel level, BlockPos origin, Template.Carriage t, CarriageDims dims) {
        return CarriageEditor.captureTemplate(level, origin, CarriageEditor.plotDims(t.variant(), dims));
    }

    @Override protected void persist(Template.Carriage t, StructureTemplate template, boolean toSource) throws IOException {
        if (toSource) {
            CarriageTemplateStore.saveToSource(t.variant(), template);
        } else {
            CarriageTemplateStore.save(t.variant(), template);
        }
    }

    /**
     * Variant sidecar: snapshot whatever the in-memory cache holds (the Z menu mutates it eagerly
     * during the session; empty maps are written as a deleted file), warn on dangling conditions,
     * then the contents store's deferred loot-prefab links. In dev mode the sidecar is promoted too.
     */
    @Override protected void sidecars(Template.Carriage t, CarriageDims dims, boolean toSource) throws IOException {
        CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(t.variant(), CarriageEditor.plotDims(t.variant(), dims));
        if (toSource) {
            sidecar.saveToSource(t.variant());
            return;
        }
        sidecar.save(t.variant());
        ContainerContentsStore.loadFor(BlockVariantPlot.carriageKey(t.variant().id())).save();
    }

    @Override protected void afterSave(ServerPlayer player, Template.Carriage t, CarriageDims dims) {
        CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(t.variant(), CarriageEditor.plotDims(t.variant(), dims));
        VariantConditionWarnings.afterSave(player, t.variant().id(), sidecar.entries(), sidecar::lockIdAt);
    }

    @Override protected String describe(Template.Carriage t) {
        return t.variant().id();
    }
}
