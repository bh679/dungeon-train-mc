package games.brennan.dungeontrain.client.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.client.LevelRendererPrewarmAccessor;
import games.brennan.dungeontrain.mixin.client.ViewAreaPrewarmInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Builds the sections around a portal destination while the player is still walking towards it.
 *
 * <h2>What it does</h2>
 * <p>Nothing new — it is vanilla's own end-of-frame path, run on sections vanilla would not have
 * picked. {@code LevelRenderer.compileSections} finishes by handing each dirty <i>visible</i> section
 * to {@code rebuildSectionAsync} and marking it clean; this does the same for the sections
 * {@link ClientPortalPrewarm} names, which are by definition not visible — they are on the other side
 * of a portal. By the time the swap fires they have meshes, and the arrival frame has something to
 * draw instead of the clear colour.</p>
 *
 * <h2>Why it can be this relaxed about failing</h2>
 * <p>Every step is allowed to answer "not now": no level, no view area (between level loads), a
 * section outside the render distance, a chunk that has not arrived, a section whose light is not
 * ready, a section that is already built. Each of those simply skips, and the span comes round again
 * on the next pass. The two arrival fixes in {@link ClientPortalSwap} are still in place underneath,
 * so the worst case of a prewarm that never manages anything is the behaviour we had before it.</p>
 *
 * <h2>The tally, and why it is here</h2>
 * <p>Those six ways of skipping are exactly why this needs to count. A prewarm that quietly skips
 * every section looks identical, in a log and on screen, to one that works — and the first in-game
 * runs could not tell "it built nothing" from "it built everything and something else is wrong",
 * which are opposite bugs with opposite fixes. So each outcome is counted and the tally is said out
 * loud while a destination is live. {@link #TRACE} turns it down without removing the counters.</p>
 *
 * <p>On the client tick rather than on a frame: the work is queued, not drawn, and a tick is a rate
 * that does not vary with how well the machine is doing.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PortalPrewarmTicker {

    /**
     * Whether to say what the prewarm did with each pass.
     *
     * <p>On while the shape of this is still being settled. It is one line a second and only while a
     * player is walking into a portal, which is the moment somebody reading the log cares about.</p>
     */
    private static final boolean TRACE = true;

    /** Ticks between tally lines — a second, so the shape of an approach is readable. */
    private static final int TRACE_PERIOD_TICKS = 20;

    /** built, noChunk, lightOff, clean, missing, compiled — see {@link #traceTally}. */
    private static int built;
    private static int noChunk;
    private static int lightOff;
    private static int clean;
    private static int missing;

    /**
     * How many of the sections looked at this pass actually hold geometry.
     *
     * <p>The number that was missing, and whose absence cost two rounds of diagnosis. The others count
     * what the prewarm <i>did</i>; this counts what came of it. A section can be neither dirty nor
     * compiled — which is what the old {@code setNotDirty} left behind — and in that state it is
     * invisible to vanilla's rebuild path and impassable to the occlusion graph, while reading as
     * "clean" and looking, in a tally, exactly like success.</p>
     */
    private static int compiled;

    /** Ticks since the last tally line, and whether anything has happened worth reporting. */
    private static int sinceTrace;
    private static boolean tallied;

    private PortalPrewarmTicker() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ClientPortalPrewarm.live()) {
            // The hint has gone quiet — say what the last of it did before the counters are reused,
            // so an approach that ended in a swap is reported rather than rolled into the next one.
            traceTally("done");
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) return;

        LevelRenderer renderer = minecraft.levelRenderer;
        ViewArea viewArea = ((LevelRendererPrewarmAccessor) renderer).dungeontrain$viewArea();
        SectionRenderDispatcher dispatcher =
            ((LevelRendererPrewarmAccessor) renderer).dungeontrain$sectionRenderDispatcher();
        if (viewArea == null || dispatcher == null) return;

        // Allocated once for the batch and only if something is actually built, the way vanilla
        // allocates one per compileSections pass — it is a cache of the chunks the builds read.
        RenderRegionCache regions = null;

        for (long section : ClientPortalPrewarm.claim()) {
            int x = ClientPortalPrewarm.sectionX(section);
            int y = ClientPortalPrewarm.sectionY(section);
            int z = ClientPortalPrewarm.sectionZ(section);
            tallied = true;

            // The client has no chunk here yet — a copy standing in columns the server has not sent.
            // Skipped rather than waited for; the next pass over the span picks it up.
            if (!level.getChunkSource().hasChunk(x, z)) {
                noChunk++;
                continue;
            }

            SectionPos pos = SectionPos.of(x, y, z);
            if (!level.getLightEngine().lightOnInSection(pos)) {
                lightOff++;
                continue;
            }

            SectionRenderDispatcher.RenderSection target =
                ((ViewAreaPrewarmInvoker) viewArea).dungeontrain$getRenderSectionAt(pos.origin());
            if (target == null) {
                missing++;
                continue;
            }
            if (!target.isDirty()) {
                clean++;
                if (target.getCompiled() != SectionRenderDispatcher.CompiledSection.UNCOMPILED) compiled++;
                continue;
            }

            if (regions == null) regions = new RenderRegionCache();
            // SYNCHRONOUS, on purpose, and the whole lesson of this file. The async form
            // (rebuildSectionAsync) begins with cancelTasks(): a span that cycles and re-schedules
            // every still-dirty section cancels its own previous task before it reaches the front
            // of the queue, and an uncompiled section's task is always LOW priority — a FIFO behind
            // the train's continuous re-meshing — so it never got there. Five arrivals into one
            // room, and the room was uncompiled on every one of them. This is what vanilla's own
            // NEARBY setting does for sections by the camera: build it here, now, and be done. It
            // cannot be cancelled and cannot be starved. A section takes about a millisecond, and
            // SECTIONS_PER_TICK bounds the frame.
            dispatcher.rebuildSectionSync(target, regions);
            target.setNotDirty();
            built++;

            if (ClientPortalPrewarm.claimFirstTrace()) {
                LogUtils.getLogger().info(
                    "[DungeonTrain] Portal prewarm: building the destination ahead of the swap");
            }
        }

        if (++sinceTrace >= TRACE_PERIOD_TICKS) traceTally("live");
    }

    /**
     * Say what the last second of prewarming came to, and start counting again.
     *
     * <p>The five outcomes are the five ways a section can fail to become geometry, and which of them
     * dominates is the whole diagnosis: {@code lightOff} means the sealed basement has no light data
     * on the client and this has never built anything; {@code built} with a flash still on screen
     * means the builds are landing somewhere that is being thrown away; {@code noChunk} means the
     * destination is not in the client's columns at all.</p>
     */
    private static void traceTally(String state) {
        sinceTrace = 0;
        if (!tallied) return;
        tallied = false;

        if (TRACE) {
            LogUtils.getLogger().info(
                "[DungeonTrain] Portal prewarm tally ({}): built={} compiled={} noChunk={} lightOff={} clean={} missing={}",
                state, built, compiled, noChunk, lightOff, clean, missing);
        }
        built = 0;
        noChunk = 0;
        lightOff = 0;
        clean = 0;
        missing = 0;
        compiled = 0;
    }
}
