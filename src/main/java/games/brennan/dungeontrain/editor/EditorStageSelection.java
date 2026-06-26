package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.template.Stage;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Locale;

/**
 * The single, <b>global</b> "which {@link Stage} is the author focused on" selection for the
 * in-game editor — the live counterpart to {@link StageStore}'s preset registry. Unlike the
 * persisted stage gates, this is purely in-memory editor UI state: it is never written to disk and
 * is cleared when the server stops.
 *
 * <p>Global (not per-player) on purpose: the carriage editor plots are shared overworld geometry at
 * a fixed high-Y location, so two OPs editing at once already see one another's stamps — a single
 * focused stage keeps the shared preview coherent, mirroring how {@link StageStore} itself is one
 * global registry.</p>
 *
 * <p>When a stage is selected, the 4-arg editor-preview
 * {@link games.brennan.dungeontrain.train.CarriagePlacer#placeAt(net.minecraft.server.level.ServerLevel,
 * net.minecraft.core.BlockPos, games.brennan.dungeontrain.train.CarriageVariant,
 * games.brennan.dungeontrain.train.CarriageDims) placeAt} keeps each carriage's base shell but, per
 * swappable part kind, stamps only the part whose {@code stageId} matches this selection and airs out
 * the slot when nothing is linked. Newly-added parts also default their {@code stageId} to this
 * selection. The empty selection ({@link #selected()} == {@code null}) restores today's preview.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorStageSelection {

    /** Currently focused stage id (lower-cased), or {@code null} when nothing is selected. */
    private static volatile String selectedStageId = null;

    private EditorStageSelection() {}

    /** The focused stage id (lower-cased), or {@code null} when no stage is selected. */
    public static String selected() {
        return selectedStageId;
    }

    /** True iff a stage is selected. */
    public static boolean hasSelection() {
        return selectedStageId != null;
    }

    /** True iff {@code id} is the currently selected stage (case-insensitive). */
    public static boolean isSelected(String id) {
        return selectedStageId != null && id != null
            && selectedStageId.equals(id.toLowerCase(Locale.ROOT));
    }

    /** Focus {@code id} (lower-cased). A blank/null id clears the selection. */
    public static void select(String id) {
        selectedStageId = (id == null || id.isBlank()) ? null : id.toLowerCase(Locale.ROOT);
    }

    /** Clear the selection (back to today's unfiltered preview). */
    public static void clear() {
        selectedStageId = null;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }
}
