package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.EditorRegionDiff;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Puts Effortless Building's block changes into the editor's own undo history, so Ctrl+Z
 * means "the last thing I did" whichever tool did it.
 *
 * <p><b>Why this is needed at all.</b> Effortless Building writes with raw
 * {@code level.setBlock} / {@code level.destroyBlock} from inside its own server-bound packet
 * handlers — it fires no NeoForge block events, exactly as {@link EffortlessBuildingGate}
 * describes for the Free Play gate. So
 * {@link games.brennan.dungeontrain.editor.EditorEditRecorder}, which listens on those
 * events, sees nothing: an author who blocked out half a carriage with EB's line or cube
 * mode and then pressed Ctrl+Z would undo their previous <em>hand-placed</em> edit while the
 * EB work stayed put. EB does have an undo of its own (plain {@code Z}, no modifier, so the
 * two bindings do not collide), but that is a second history to keep in your head.</p>
 *
 * <p><b>How.</b> {@link EditorRegionDiff} already records whole-plot operations by scanning
 * the plot before and after and keeping only the cells that moved — a mechanism that needs no
 * knowledge of how the operation writes, which is what makes it fit another mod's code. Its
 * wrapping form takes a {@code Runnable}, and a Mixin on someone else's method cannot supply
 * one, so this drives {@link EditorRegionDiff#open} and {@link EditorRegionDiff#close} from
 * the two halves of an injector pair instead.</p>
 *
 * <p><b>Only inside an editor plot.</b> {@code open} returns null when the player is standing
 * outside every plot, which is the rule every other producer follows. EB building out past
 * the plot bounds is not undoable, just as hand-placing out there is not.</p>
 *
 * <p><b>Server thread only.</b> Effortless Building dispatches all of its server-bound packets
 * through {@code context.enqueueWork}, so every call here lands on the main thread — hence a
 * plain {@link HashMap}, matching
 * {@link games.brennan.dungeontrain.editor.EditorEditRecorder}.</p>
 *
 * <p><b>Fails open, always.</b> Every entry point swallows its own failures: a history that
 * cannot be recorded must never cost the player their build. The worst case is a build that
 * cannot be undone with Ctrl+Z, which is exactly where things stood before this existed.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EffortlessBuildingHistory {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Step labels, plain English to match "Mirror rebuild" / "Block swap" elsewhere. */
    public static final String PLACE = "Effortless Building place";
    public static final String BREAK = "Effortless Building break";
    public static final String UNDO = "Effortless Building undo";
    public static final String REDO = "Effortless Building redo";

    /** player → the capture opened for the action currently running. Server-thread only. */
    private static final Map<UUID, EditorRegionDiff.Capture> PENDING = new HashMap<>();

    private EffortlessBuildingHistory() {}

    /**
     * Open a capture over the player's plot ahead of an Effortless Building action.
     *
     * <p>Any capture still open for this player is dropped first. That is the recovery path
     * for the one way a capture can leak — Effortless Building's handler throwing part-way,
     * which skips {@link #end}. Reusing the stale one would describe a world two builds old,
     * so it goes.</p>
     */
    public static void begin(ServerPlayer player, String label) {
        if (player == null) return;
        try {
            EditorRegionDiff.Capture stale = PENDING.remove(player.getUUID());
            if (stale != null) {
                LOGGER.debug("[DungeonTrain] Discarding a stale Effortless Building capture over {}"
                    + " — the previous action did not finish cleanly.", stale.plotKey());
            }
            EditorRegionDiff.Capture capture = EditorRegionDiff.open(player, label, null);
            if (capture != null) PENDING.put(player.getUUID(), capture);
        } catch (Throwable t) {
            PENDING.remove(player.getUUID());
            LOGGER.debug("[DungeonTrain] Could not record an Effortless Building action — it will"
                + " not be undoable: {}", t.toString());
        }
    }

    /**
     * Close the capture {@link #begin} opened, pushing one undo step for everything the action
     * changed inside the plot.
     *
     * <p><b>Nothing open is a no-op</b>, and that is load-bearing rather than merely defensive:
     * the {@code RETURN} injector that calls this fires on Effortless Building's early returns,
     * and may or may not also fire on the synthetic return of a cancelled Free Play prompt,
     * depending on the order Mixin happens to apply the two injectors in. Neither case has a
     * capture, and both must be harmless.</p>
     */
    public static void end(ServerPlayer player) {
        if (player == null) return;
        EditorRegionDiff.Capture capture = PENDING.remove(player.getUUID());
        if (capture == null) return;
        try {
            EditorRegionDiff.close(player, capture);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Could not close an Effortless Building capture over {} —"
                + " that action will not be undoable: {}", capture.plotKey(), t.toString());
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }
}
