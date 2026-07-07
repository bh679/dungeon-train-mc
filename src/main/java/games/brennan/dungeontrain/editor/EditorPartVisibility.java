package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The single, <b>global</b> per-part "displayed in the editor grid" state — the source of truth for
 * which part plots {@link CarriagePartEditor} stamps. A part is displayed unless it is in the
 * {@link #hidden} set. Purely in-memory editor UI state: never persisted, cleared on server stop.
 *
 * <p>Edited three ways:
 * <ul>
 *   <li><b>Hide-unused toggle</b> ({@link EditorPartsStageFilter}) — {@link #hideUnused} snapshots
 *       the focused stage's parts as the only displayed ones; {@link #showAll} clears the set. This
 *       is a one-shot action on the toggle, so manual overrides made afterward stick.</li>
 *   <li><b>Manual per-part checkbox</b> — {@link #toggle}/{@link #setHidden} from the part list.</li>
 *   <li><b>New parts</b> — {@link #show} on create, so a freshly-authored part is always displayed
 *       (exempt from a prior hide-unused snapshot).</li>
 * </ul></p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorPartVisibility {

    private static final Set<StageBlockIndex.PartRef> hidden = ConcurrentHashMap.newKeySet();
    private static final AtomicLong GENERATION = new AtomicLong();

    private EditorPartVisibility() {}

    /** Monotonic change counter — moves on every visibility mutation (drives the client sync dedup). */
    public static long generation() {
        return GENERATION.get();
    }

    /** True when the part is displayed (stamped) in the editor grid. */
    public static boolean isDisplayed(CarriagePartKind kind, String name) {
        return !hidden.contains(new StageBlockIndex.PartRef(kind, name));
    }

    /** A snapshot of the currently-hidden parts — for the client sync packet. */
    public static Set<StageBlockIndex.PartRef> hiddenSnapshot() {
        return new HashSet<>(hidden);
    }

    /** Flip a part's displayed state; returns the new displayed value. */
    public static boolean toggle(CarriagePartKind kind, String name) {
        StageBlockIndex.PartRef ref = new StageBlockIndex.PartRef(kind, name);
        boolean nowHidden = hidden.contains(ref) ? !hidden.remove(ref) : hidden.add(ref);
        GENERATION.incrementAndGet();
        return !nowHidden;
    }

    /** Set a part hidden or displayed explicitly. */
    public static void setHidden(CarriagePartKind kind, String name, boolean hide) {
        StageBlockIndex.PartRef ref = new StageBlockIndex.PartRef(kind, name);
        boolean changed = hide ? hidden.add(ref) : hidden.remove(ref);
        if (changed) GENERATION.incrementAndGet();
    }

    /** Ensure a part is displayed — the new-part exemption. */
    public static void show(CarriagePartKind kind, String name) {
        setHidden(kind, name, false);
    }

    /**
     * Snapshot the focused stage's parts as the only displayed ones: hide every registered part not
     * linked to {@code stageId} (per {@link StageBlockIndex#partsForStage}). The hide-unused ON
     * action.
     */
    public static void hideUnused(String stageId) {
        // No focused stage → nothing to filter to; show all rather than hiding every part.
        if (stageId == null || stageId.isBlank()) {
            showAll();
            return;
        }
        Set<StageBlockIndex.PartRef> used = new HashSet<>(StageBlockIndex.partsForStage(stageId));
        Set<StageBlockIndex.PartRef> next = new HashSet<>();
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            for (String name : CarriagePartRegistry.registeredNames(kind)) {
                StageBlockIndex.PartRef ref = new StageBlockIndex.PartRef(kind, name);
                if (!used.contains(ref)) next.add(ref);
            }
        }
        hidden.clear();
        hidden.addAll(next);
        GENERATION.incrementAndGet();
    }

    /** Display every part — the hide-unused OFF action. */
    public static void showAll() {
        if (!hidden.isEmpty()) {
            hidden.clear();
            GENERATION.incrementAndGet();
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        hidden.clear();
        GENERATION.incrementAndGet();
    }
}
