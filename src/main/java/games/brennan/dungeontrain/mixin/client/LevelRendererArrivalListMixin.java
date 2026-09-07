package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.portal.ClientPortalPrewarm;
import games.brennan.dungeontrain.client.portal.ClientPortalSwap;
import games.brennan.dungeontrain.client.portal.PortalArrivalTrace;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Lists the portal destination for drawing the moment the player lands in it, without waiting for the
 * occlusion graph to find it.
 *
 * <h2>The last piece of the flash</h2>
 * <p>By the time a swap fires, the destination's sections are built ({@code PortalPrewarmTicker}) and
 * the visible list is re-derived every frame ({@code LevelRendererFrustumRefreshMixin}). What is
 * still missing on the arrival frame is the <i>graph</i> those re-derives read from: the occlusion
 * walk from the new camera does not reach the room in one pass, and the list then fills in a ring at
 * a time as {@code onSectionCompiled} feeds {@code runPartialUpdate} — measured at 0 → 45 → 901
 * sections over 315 ms. That climb is the flash, and it is the graph being late, not the meshes.</p>
 *
 * <h2>What this does</h2>
 * <p>After every {@code applyFrustum} during the arrival window, appends the destination's compiled
 * sections straight into {@code visibleSections}, graph or no graph. Only ones the frustum accepts,
 * only ones with something to draw, only ones not already listed. The room is a sealed box: there is
 * nothing inside it for occlusion to cull, so listing all of it is not an approximation but the answer
 * the graph would reach a few frames later. The seal still runs inside {@code Frustum.isVisible}, so
 * nothing on the far side of a bedrock cut is ever added.</p>
 *
 * <p>Brennan's framing of it: "render from the other camera before we switch". Seeding the graph from
 * the destination would be the literal version, but the graph is thrown away and re-walked every
 * eight blocks of train travel, so a second seed is as incomplete as the arrival walk. Listing the
 * sections directly is the same idea one step further along, and it cannot be late.</p>
 *
 * <p>Cost: a few dozen sections checked per frame for 400 ms, once per swap. {@code require = 0}
 * like its siblings — Sodium replaces this path wholesale and vanishing quietly is right there.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererArrivalListMixin {

    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Shadow
    private ViewArea viewArea;

    @Inject(method = "applyFrustum", at = @At("TAIL"), require = 0)
    private void dungeontrain$listDestinationOnArrival(Frustum frustum, CallbackInfo ci) {
        if (!ClientPortalSwap.inArrivalWindow()) return;
        long[] span = ClientPortalPrewarm.span();
        if (span.length == 0 || this.viewArea == null) return;

        // Identity, not equals: RenderSection has no equals override worth paying for, and the list is
        // hundreds long on a normal frame — a per-section linear scan would be the expensive part.
        Set<SectionRenderDispatcher.RenderSection> listed =
            Collections.newSetFromMap(new IdentityHashMap<>(this.visibleSections.size() * 2));
        listed.addAll(this.visibleSections);

        ViewAreaPrewarmInvoker area = (ViewAreaPrewarmInvoker) this.viewArea;
        int added = 0;
        for (long packed : span) {
            SectionRenderDispatcher.RenderSection section = area.dungeontrain$getRenderSectionAt(
                SectionPos.of(ClientPortalPrewarm.sectionX(packed), ClientPortalPrewarm.sectionY(packed),
                    ClientPortalPrewarm.sectionZ(packed)).origin());
            if (section == null || listed.contains(section)) continue;

            SectionRenderDispatcher.CompiledSection compiled = section.getCompiled();
            if (compiled == SectionRenderDispatcher.CompiledSection.UNCOMPILED
                || compiled.hasNoRenderableLayers()) continue;
            if (!frustum.isVisible(section.getBoundingBox())) continue;

            this.visibleSections.add(section);
            listed.add(section);
            added++;
        }
        PortalArrivalTrace.noteListed(added);
    }
}
