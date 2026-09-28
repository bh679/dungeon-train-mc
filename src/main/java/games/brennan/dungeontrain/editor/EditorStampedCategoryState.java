package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Optional;

/**
 * Server-side memory of which {@link EditorCategory} is currently stamped in
 * the editor world — the <b>resident</b> category. Set whenever
 * {@code runEnterCategory} stamps a fresh category's plots; cleared whenever
 * {@link EditorCategory#clearAllPlots} tears them down (category switch or
 * {@code /dt editor exit}).
 *
 * <p>Every category lays its plots out from the same origin ({@link EditorLayout}), so the
 * question "which plot is at this position" has no answer without knowing which category is
 * resident. {@link #isActive} is that answer's gate: each editor's {@code plotContaining} returns
 * nothing unless its category is the resident one. With no resident category there are no plots
 * anywhere, whatever the layout would predict.</p>
 *
 * <p>Drives {@link games.brennan.dungeontrain.editor.VariantOverlayRenderer}'s
 * label dispatch so the floating name+weight panels persist for as long as the
 * structures themselves are present in the world — not just while the player
 * is standing inside one of the cages.</p>
 *
 * <p>Mirrored into {@link DungeonTrainWorldData} so it survives a restart: the plots are still
 * standing in the sky after a reload, and without the record they would answer to nothing until
 * the author re-entered the category. Restored from the overworld on {@link ServerStartedEvent}.</p>
 *
 * <p>Volatile global because plot stamping happens on the server thread but
 * the snapshot dispatch reads it on every tick from the same thread; the
 * volatile keeps the field cheap and consistent without adding a lock to the
 * tight per-tick path.</p>
 *
 * <p>The integrated server starts and stops within one JVM, so this static
 * field would otherwise leak across worlds: enter the editor, "Save and Quit
 * to Title", open a different world, and the next server tick would push
 * phantom plot labels for the previously-stamped category. The
 * {@link ServerStoppedEvent} hook below resets the field on every world quit;
 * the next world's own record is loaded when its server starts.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorStampedCategoryState {

    private static volatile EditorCategory current = null;

    private EditorStampedCategoryState() {}

    /** Mark {@code category} as the resident editor view, and record it in the world. */
    public static void set(ServerLevel overworld, EditorCategory category) {
        current = category;
        if (overworld != null) {
            DungeonTrainWorldData.get(overworld).setEditorStampedCategory(category == null ? "" : category.id());
        }
    }

    /** Forget any resident category — labels should clear, no plot answers anywhere. */
    public static void clear(ServerLevel overworld) {
        set(overworld, null);
    }

    /** The resident category, or empty if no category is active. */
    public static Optional<EditorCategory> current() {
        return Optional.ofNullable(current);
    }

    /**
     * Whether {@code category}'s plots are the ones standing in the world — the gate on every
     * editor's {@code plotContaining}. Strict: with no resident category nothing is active.
     */
    public static boolean isActive(EditorCategory category) {
        return category != null && current == category;
    }

    /** Load the record the world carries — the category still standing from before a restart. */
    public static void restore(ServerLevel overworld) {
        String id = DungeonTrainWorldData.get(overworld).editorStampedCategory();
        current = EditorCategory.fromId(id).orElse(null);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ServerLevel overworld = event.getServer().overworld();
        if (overworld != null) restore(overworld);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        current = null;
    }
}
