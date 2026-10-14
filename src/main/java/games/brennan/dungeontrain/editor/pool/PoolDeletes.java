package games.brennan.dungeontrain.editor.pool;

import games.brennan.dungeontrain.editor.CarriageContentsEditor;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.ShellWinsStore;
import games.brennan.dungeontrain.editor.TemplateDeletes;
import games.brennan.dungeontrain.editor.TemplateSizeStore;
import games.brennan.dungeontrain.editor.WholeCarriageEditor;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
import games.brennan.dungeontrain.editor.WholeHalfTemplateStore;
import games.brennan.dungeontrain.editor.WholeTemplateNew;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.ContentsSize;
import games.brennan.dungeontrain.train.ShellPool;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeKind;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.util.List;

/**
 * Remove one template from its pool: its plot (when that category is on show), its file, its
 * sidecars and weight, its registry entry, and the gap it leaves in its row. The one body behind
 * the editor's {@code reset} commands and the far side of a {@link PoolMove}.
 */
public final class PoolDeletes {

    private PoolDeletes() {}

    /** What a removal found and did. */
    public record Removed(boolean existed, boolean wasCustom, TemplateDeletes.Report cleanup) {}

    /**
     * Remove a carriage shell. {@code shown} says whether the Carriages category is stamped in the
     * world right now — only then does its plot get cleared and its row closed up.
     */
    public static Removed carriage(ServerLevel overworld, CarriageDims dims, CarriageVariant variant, boolean shown)
            throws IOException {
        // Where its plot stood, taken before it leaves the registry and its row closes up.
        CarriageEditor.RowSpot oldSpot = shown ? CarriageEditor.spotOf(variant, dims) : null;
        if (shown) CarriageStampGuard.run(() -> CarriageEditor.clearPlot(overworld, variant, dims));
        boolean deleted = CarriageTemplateStore.delete(variant);
        TemplateDeletes.Report cleanup = TemplateDeletes.carriage(variant);
        boolean wasCustom = !variant.isBuiltin();
        if (wasCustom) {
            CarriageVariantRegistry.unregister(variant.id());
            TemplateSizeStore.SHELLS.forget(variant.id());
            ShellWinsStore.forget(variant.id());
            ShellPool.forget(variant.id());
            if (oldSpot != null) {
                CarriageStampGuard.run(() -> CarriageEditor.restampRowAfterDeletion(overworld, oldSpot, dims));
            }
        }
        return new Removed(deleted, wasCustom, cleanup);
    }

    /** Remove a whole room, half or group. {@code shown}: whether the Whole category is on show. */
    public static Removed whole(ServerLevel overworld, CarriageDims dims, WholeKind kind, Template model, boolean shown)
            throws IOException {
        String id = model.id();
        boolean existed = switch (kind) {
            case GROUP -> CarriageGroupTemplateStore.delete(new CarriageGroup(id));
            case HALF -> WholeHalfTemplateStore.delete(new WholeCarriage(id));
            case ROOM -> WholeCarriageTemplateStore.delete(new WholeCarriage(id));
        };
        boolean wasCustom = !model.isBuiltin();
        TemplateDeletes.Report cleanup = TemplateDeletes.whole(kind, id, wasCustom);
        if (wasCustom) {
            WholeTemplateNew.unregister(kind, id);
            if (shown) {
                // The row past it shifts one slot left; restamp what is left standing.
                List<Template> rest = EditorCategory.WHOLE.models();
                CarriageStampGuard.run(() -> {
                    for (Template m : rest) WholeCarriageEditor.clearPlot(overworld, m, dims);
                    WholeCarriageEditor.clearPlot(overworld, model, dims);
                    for (Template m : rest) WholeCarriageEditor.stampPlot(overworld, m, dims);
                });
            }
        } else if (shown) {
            CarriageStampGuard.run(() -> WholeCarriageEditor.stampPlot(overworld, model, dims));
        }
        return new Removed(existed, wasCustom, cleanup);
    }

    /** Remove a contents template. {@code shown}: whether the Contents category is on show. */
    public static Removed contents(ServerLevel overworld, CarriageDims dims, CarriageContents contents, boolean shown)
            throws IOException {
        // Only a top-level template holds a row slot; its row is its size's. Capture where every plot
        // after it stands now, so the restamp can clear exactly those once the row closes up.
        ContentsSize rowSize = CarriageContentsPlacer.sizeOf(contents);
        int oldSlot = shown ? CarriageContentsEditor.topLevelSlotOf(contents.id()) : -1;
        List<CarriageContentsEditor.StandingPlot> standing = oldSlot >= 0
            ? CarriageContentsEditor.plotsFrom(rowSize, oldSlot, dims) : List.of();

        if (shown) CarriageStampGuard.run(() -> CarriageContentsEditor.clearPlot(overworld, contents, dims));
        boolean deleted = CarriageContentsStore.delete(contents);
        TemplateDeletes.Report cleanup = TemplateDeletes.contents(contents);
        boolean wasCustom = !contents.isBuiltin();
        if (wasCustom) {
            CarriageContentsRegistry.unregister(contents.id());
            TemplateSizeStore.CONTENTS.forget(contents.id());
            if (oldSlot >= 0) {
                CarriageStampGuard.run(() ->
                    CarriageContentsEditor.restampRowAfterDeletion(overworld, rowSize, oldSlot, standing, dims));
            }
        }
        return new Removed(deleted, wasCustom, cleanup);
    }
}
