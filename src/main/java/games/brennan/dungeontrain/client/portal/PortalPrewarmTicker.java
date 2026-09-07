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
 * <p>On the client tick rather than on a frame: the work is queued, not drawn, and a tick is a rate
 * that does not vary with how well the machine is doing.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PortalPrewarmTicker {

    private PortalPrewarmTicker() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ClientPortalPrewarm.live()) return;

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

            // The client has no chunk here yet — a copy standing in columns the server has not sent.
            // Skipped rather than waited for; the next pass over the span picks it up.
            if (!level.getChunkSource().hasChunk(x, z)) continue;

            SectionPos pos = SectionPos.of(x, y, z);
            if (!level.getLightEngine().lightOnInSection(pos)) continue;

            SectionRenderDispatcher.RenderSection target =
                ((ViewAreaPrewarmInvoker) viewArea).dungeontrain$getRenderSectionAt(pos.origin());
            if (target == null || !target.isDirty()) continue;

            if (regions == null) regions = new RenderRegionCache();
            target.rebuildSectionAsync(dispatcher, regions);
            target.setNotDirty();

            if (ClientPortalPrewarm.claimFirstTrace()) {
                LogUtils.getLogger().info(
                    "[DungeonTrain] Portal prewarm: building the destination ahead of the swap");
            }
        }
    }
}
