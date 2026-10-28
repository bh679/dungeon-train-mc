package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageDoorCells;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriageGroupPlacer;
import games.brennan.dungeontrain.train.CarriageGroupRegistry;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeCarriagePlacer;
import games.brennan.dungeontrain.train.WholeCarriageRegistry;
import games.brennan.dungeontrain.train.WholeHalfRegistry;
import games.brennan.dungeontrain.train.WholeKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.IOException;
import java.util.List;

/**
 * The Whole section's plot editors — rooms, groups and halves, one row each, one small subclass
 * each. Layout stays on {@link WholeCarriageEditor} (its {@code plotSize} / {@code rowOrigin} are
 * what the type menus anchor on); the lifecycle is {@link PlotEditor}'s.
 *
 * @param <T> the Whole record this row holds
 */
public abstract class WholePlotEditor<T extends Template> extends PlotEditor<T> {

    private static final WholePlotEditor<Template.WholeCarriage> ROOM = new Room();
    private static final WholePlotEditor<Template.CarriageGroup> GROUP = new Group();
    private static final WholePlotEditor<Template.WholeHalf> HALF = new Half();

    public static WholePlotEditor<Template.WholeCarriage> room() { return ROOM; }
    public static WholePlotEditor<Template.CarriageGroup> group() { return GROUP; }
    public static WholePlotEditor<Template.WholeHalf> half() { return HALF; }

    public static WholePlotEditor<? extends Template> forKind(WholeKind kind) {
        return switch (kind) {
            case ROOM -> ROOM;
            case GROUP -> GROUP;
            case HALF -> HALF;
        };
    }

    protected final WholeKind kind;

    private WholePlotEditor(WholeKind kind) {
        this.kind = kind;
    }

    /** The record for a registered {@code id} on this row. */
    public abstract T model(String id);

    /** The ids on this row, in slot order. */
    protected abstract List<String> ids();

    /** The box the placer erases — the half's is its own, longer box. */
    protected abstract CarriageDims eraseBox(CarriageDims dims);

    /** Record {@code t} in its registry once it has a saved template. */
    protected abstract void register(T t);

    @Override public final EditorCategory category() {
        return EditorCategory.WHOLE;
    }

    @Override public final String snapshotKey(T t) {
        return WholeCarriageEditor.snapshotKey(kind, t.id());
    }

    @Override public final BlockPos origin(T t, CarriageDims dims) {
        return WholeCarriageEditor.plotOrigin(kind, t.id(), dims);
    }

    @Override public final Vec3i size(T t, CarriageDims dims) {
        return WholeCarriageEditor.plotSize(kind, dims);
    }

    @Override public final List<T> resident(CarriageDims dims) {
        return ids().stream().map(this::model).toList();
    }

    @Override protected final BlockPos door(BlockPos origin, T t, CarriageDims dims) {
        // A half's doorways sit on its own, longer box.
        return EditorPlotArrival.firstOrNull(CarriageDoorCells.doorBases(origin, eraseBox(dims)));
    }

    @Override protected final void beforeStamp(T t) {
        // Re-read the Z/C sidecars from disk on every stamp, like every other plot editor.
        WholeVariantBlocks.invalidate(kind, t.id());
        ContainerContentsStore.invalidate(BlockVariantPlot.wholeKey(kind, t.id()));
    }

    @Override protected void erase(ServerLevel level, BlockPos origin, T t, CarriageDims dims) {
        CarriagePlacer.eraseAt(level, origin, eraseBox(dims));
        EditorPlotCage.setOutline(level, origin, size(t, dims), Blocks.AIR.defaultBlockState());
    }

    /**
     * The Z menu's variant pools and the C menu's container links — the in-session edits both
     * menus accumulate until Save, as {@code CarriageEditor.save} does.
     */
    @Override protected final void sidecars(T t, CarriageDims dims, boolean toSource) throws IOException {
        if (toSource) return;
        WholeVariantBlocks.loadFor(kind, t.id(), size(t, dims)).save(kind, t.id());
        ContainerContentsStore.loadFor(BlockVariantPlot.wholeKey(kind, t.id())).save();
    }

    @Override protected final String describe(T t) {
        return kind.id() + " " + t.id();
    }

    // ---- the three rows ----------------------------------------------------------------------

    private static final class Room extends WholePlotEditor<Template.WholeCarriage> {
        Room() { super(WholeKind.ROOM); }
        @Override public Template.WholeCarriage model(String id) { return new Template.WholeCarriage(new WholeCarriage(id)); }
        @Override protected List<String> ids() { return WholeCarriageRegistry.ids(); }
        @Override protected CarriageDims eraseBox(CarriageDims dims) { return dims; }
        @Override protected void register(Template.WholeCarriage t) { WholeCarriageRegistry.register(t.wholeCarriage()); }
        @Override protected void place(ServerLevel level, BlockPos origin, Template.WholeCarriage t, CarriageDims dims) {
            WholeCarriagePlacer.placeAt(level, origin, t.wholeCarriage(), dims);
        }
        @Override protected StructureTemplate capture(ServerLevel level, BlockPos origin, Template.WholeCarriage t, CarriageDims dims) {
            return WholeCarriagePlacer.captureTemplate(level, origin, dims);
        }
        @Override protected void persist(Template.WholeCarriage t, StructureTemplate template, boolean toSource) throws IOException {
            if (toSource) {
                WholeCarriageTemplateStore.saveToSource(t.wholeCarriage(), template);
            } else {
                WholeCarriageTemplateStore.save(t.wholeCarriage(), template);
                register(t);
            }
        }
    }

    private static final class Half extends WholePlotEditor<Template.WholeHalf> {
        Half() { super(WholeKind.HALF); }
        @Override public Template.WholeHalf model(String id) { return new Template.WholeHalf(new WholeCarriage(id)); }
        @Override protected List<String> ids() { return WholeHalfRegistry.ids(); }
        @Override protected CarriageDims eraseBox(CarriageDims dims) { return WholeHalfTemplateStore.halfDims(dims); }
        @Override protected void register(Template.WholeHalf t) { WholeHalfRegistry.register(t.wholeCarriage()); }
        @Override protected void place(ServerLevel level, BlockPos origin, Template.WholeHalf t, CarriageDims dims) {
            WholeCarriagePlacer.placeHalfAt(level, origin, t.wholeCarriage(), dims);
        }
        @Override protected StructureTemplate capture(ServerLevel level, BlockPos origin, Template.WholeHalf t, CarriageDims dims) {
            return WholeCarriagePlacer.captureTemplate(level, origin, WholeHalfTemplateStore.halfDims(dims));
        }
        @Override protected void persist(Template.WholeHalf t, StructureTemplate template, boolean toSource) throws IOException {
            if (toSource) {
                WholeHalfTemplateStore.saveToSource(t.wholeCarriage(), template);
            } else {
                WholeHalfTemplateStore.save(t.wholeCarriage(), template);
                register(t);
            }
        }
    }

    private static final class Group extends WholePlotEditor<Template.CarriageGroup> {
        Group() { super(WholeKind.GROUP); }
        @Override public Template.CarriageGroup model(String id) { return new Template.CarriageGroup(new CarriageGroup(id)); }
        @Override protected List<String> ids() { return CarriageGroupRegistry.ids(); }
        @Override protected CarriageDims eraseBox(CarriageDims dims) { return dims; }
        @Override protected void register(Template.CarriageGroup t) { CarriageGroupRegistry.register(t.group()); }
        /** A group plot is {@code groupSize} carriages long; the group placer erases the whole span. */
        @Override protected void erase(ServerLevel level, BlockPos origin, Template.CarriageGroup t, CarriageDims dims) {
            CarriageGroupPlacer.eraseAt(level, origin, dims, WholeCarriageEditor.groupSize());
            EditorPlotCage.setOutline(level, origin, size(t, dims), Blocks.AIR.defaultBlockState());
        }
        @Override protected void place(ServerLevel level, BlockPos origin, Template.CarriageGroup t, CarriageDims dims) {
            CarriageGroupPlacer.placeAt(level, origin, t.group(), dims, WholeCarriageEditor.groupSize());
        }
        @Override protected StructureTemplate capture(ServerLevel level, BlockPos origin, Template.CarriageGroup t, CarriageDims dims) {
            return CarriageGroupPlacer.captureTemplate(level, origin, dims, WholeCarriageEditor.groupSize());
        }
        @Override protected void persist(Template.CarriageGroup t, StructureTemplate template, boolean toSource) throws IOException {
            if (toSource) {
                CarriageGroupTemplateStore.saveToSource(t.group(), template);
            } else {
                CarriageGroupTemplateStore.save(t.group(), template);
                register(t);
            }
        }
    }
}
