package games.brennan.dungeontrain.mixin.client.sodium;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.portal.ClientPortalPrewarm;
import games.brennan.dungeontrain.client.portal.PortalArrivalTrace;
import games.brennan.dungeontrain.client.portal.SodiumPrewarmSection;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.Map;

/**
 * The dimensional-carriage prewarm on <b>Sodium's</b> renderer — what {@code PortalPrewarmTicker}
 * does for vanilla. That ticker stands down when Sodium is loaded: Sodium 0.8 leaves vanilla's view
 * area in place, so the ticker would otherwise mesh sections on the main thread that Sodium never
 * draws.
 *
 * <h2>Why the queue, and not a public call</h2>
 * <p>Sodium builds a section only once its occlusion walk reaches it. For a sealed room far below
 * bedrock that is the frame the player arrives, so the first visit to any carriage pops in. Its only
 * public way to ask for a build, {@code scheduleRebuild}, returns early unless the section is
 * <em>already</em> built — it cannot start a first build, and even for a built section it only
 * stamps a pending update that still waits for the walk. Either way the section carries a pending
 * update — {@code INITIAL_BUILD} from {@code onSectionAdded}, or a rebuild from the block updates
 * that place the room into chunks the client already held — and nothing ever queues it. {@code updateChunks} drains one deque per queue type, skipping
 * entries whose pending update is zero, and submits the rest to Sodium's own builder under its own
 * budget. So this pushes a few of the destination's pending sections into the this-frame deque at
 * the head of {@code updateChunks} — after the walk has filled it, before it is drained — and Sodium
 * does everything else: meshing, upload, and the light-update rebuilds it already schedules for
 * built sections. See {@link #DUNGEONTRAIN$THIS_FRAME_QUEUE} for why that deque and not the
 * initial-build one.</p>
 *
 * <p>A section the walk also listed is polled twice; the second poll finds its pending update
 * cleared and skips it.</p>
 *
 * <p>Both shadows erase to types on the vanilla classpath. A shadow that no longer exists is a
 * <b>fatal</b> apply error, so {@code SodiumMixinPlugin} applies this only on Sodium {@code 0.8.*};
 * elsewhere the prewarm stands down and the first-visit flash returns, but the game launches.</p>
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class RenderSectionManagerPrewarmMixin {

    /**
     * Sodium's queue for builds that must finish <em>this</em> frame, by its enum constant's name.
     *
     * <p>Not {@code INITIAL_BUILD}: that deque is drained last, under a per-frame upload budget the
     * visible queues have usually spent, and it is not cleared between frames — measured 2026-09-08,
     * fifty sections re-pushed every frame piled up to nearly six thousand entries while the budget
     * let ten through per second, and the arrival still popped. {@code ZERO_FRAME_DEFER} is drained
     * first, on a collector with no budget, and awaited before the frame draws; a section pushed here
     * is built now and its pending update cleared now, so the next frame skips it.</p>
     */
    @Unique
    private static final String DUNGEONTRAIN$THIS_FRAME_QUEUE = "ZERO_FRAME_DEFER";

    /**
     * Most sections pushed per frame. The build is synchronous, so this is the hitch cap: a room's
     * fifty sections spread over seven or eight frames of a walk that lasts a second or more.
     */
    @Unique
    private static final int DUNGEONTRAIN$SECTIONS_PER_FRAME = 8;

    @Shadow(remap = false)
    @Final
    private Long2ReferenceMap<?> sectionByPosition;

    @Shadow(remap = false)
    private Map<?, ArrayDeque<Object>> taskLists;

    /**
     * The this-frame queue's key, resolved once; stays null if Sodium's queue types changed.
     *
     * <p>The <em>key</em>, never the deque: after every graph walk Sodium takes fresh deques from its
     * section collector, so a deque cached on one frame is an orphan on the next. Measured
     * 2026-09-08: eight sections built on the first frame and none after, while the orphan grew by
     * eight a frame.</p>
     */
    @Unique
    private Object dungeontrain$thisFrameKey;

    @Unique
    private boolean dungeontrain$keyResolved;

    @Inject(method = "updateChunks", at = @At("HEAD"), remap = false)
    private void dungeontrain$queueDestinationBuilds(CallbackInfo ci) {
        if (!ClientPortalPrewarm.live()) return;
        ArrayDeque<Object> queue = dungeontrain$thisFrameQueue();
        if (queue == null) {
            if (PortalArrivalTrace.TRACE && dungeontrain$claimTraceFrame()) {
                LogUtils.getLogger().info("[DungeonTrain] Portal prewarm (sodium): no queue resolved");
            }
            return;
        }

        int queued = 0, idle = 0, missing = 0;
        for (long packed : ClientPortalPrewarm.span()) {
            long key = SectionPos.asLong(ClientPortalPrewarm.sectionX(packed),
                                         ClientPortalPrewarm.sectionY(packed),
                                         ClientPortalPrewarm.sectionZ(packed));
            Object section = this.sectionByPosition.get(key);
            if (section == null) {
                missing++; // the client has no chunk here yet; the next frame's pass picks it up
                continue;
            }
            if (!(section instanceof SodiumPrewarmSection candidate)
                || !candidate.dungeontrain$wantsBuild()) {
                idle++; // nothing pending: built and current, or a job already running
                continue;
            }
            queue.addLast(section);
            queued++;
            if (queued >= DUNGEONTRAIN$SECTIONS_PER_FRAME) break;
        }
        if (PortalArrivalTrace.TRACE && dungeontrain$claimTraceFrame()) {
            LogUtils.getLogger().info(
                "[DungeonTrain] Portal prewarm (sodium): queued={} idle={} missing={} of {} "
                    + "(queue size now {})",
                queued, idle, missing, ClientPortalPrewarm.span().length, queue.size());
        }
    }

    /** The span array the last traced frames belonged to — a new arm starts a new budget. */
    @Unique
    private long[] dungeontrain$tracedSpan;

    @Unique
    private int dungeontrain$tracedFrames;

    /** Most frames worth reporting per arm: enough to watch a span drain, not enough to flood. */
    @Unique
    private static final int DUNGEONTRAIN$TRACE_FRAMES = 12;

    @Unique
    private boolean dungeontrain$claimTraceFrame() {
        long[] span = ClientPortalPrewarm.span();
        if (span != dungeontrain$tracedSpan) {
            dungeontrain$tracedSpan = span;
            dungeontrain$tracedFrames = 0;
        }
        if (dungeontrain$tracedFrames >= DUNGEONTRAIN$TRACE_FRAMES) return false;
        dungeontrain$tracedFrames++;
        return true;
    }

    /** This frame's this-frame deque, or null when Sodium has none to offer. */
    @Unique
    private ArrayDeque<Object> dungeontrain$thisFrameQueue() {
        Map<?, ArrayDeque<Object>> lists = this.taskLists;
        if (lists == null) return null;
        Object key = dungeontrain$thisFrameKey(lists);
        return key == null ? null : lists.get(key);
    }

    @Unique
    private Object dungeontrain$thisFrameKey(Map<?, ArrayDeque<Object>> lists) {
        if (dungeontrain$keyResolved) return dungeontrain$thisFrameKey;
        dungeontrain$keyResolved = true;
        for (Object key : lists.keySet()) {
            if (key instanceof Enum<?> type && DUNGEONTRAIN$THIS_FRAME_QUEUE.equals(type.name())) {
                dungeontrain$thisFrameKey = key;
                return key;
            }
        }
        LogUtils.getLogger().warn(
            "[DungeonTrain] Sodium has no {} task queue; the dimensional-carriage prewarm is off",
            DUNGEONTRAIN$THIS_FRAME_QUEUE);
        return null;
    }
}
