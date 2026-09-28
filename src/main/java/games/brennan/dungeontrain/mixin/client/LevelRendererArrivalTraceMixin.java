package games.brennan.dungeontrain.mixin.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.ClientPortalCrossing;
import games.brennan.dungeontrain.client.ClientPortalRoomFog;
import games.brennan.dungeontrain.client.ClientPortalRoomSky;
import games.brennan.dungeontrain.client.ClientPortalSeal;
import games.brennan.dungeontrain.client.portal.ClientPortalSwap;
import games.brennan.dungeontrain.client.portal.PortalArrivalTrace;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reports what {@code setupRender} left behind on each frame of a portal arrival — the measurement
 * {@link PortalArrivalTrace} explains, and the thing that separates the remaining hypotheses.
 *
 * <p>Reads {@code visibleSections} after vanilla has finished with it, which is the list the terrain
 * layers are drawn from a few lines later. If it is full and the screen is empty, the fault is past
 * this point (meshes, or the draw itself); if it is empty, the fault is the graph or the frustum that
 * filtered it, and {@code sealed} says which.</p>
 *
 * <p>Diagnostic, not a fix: no behaviour of any kind, and budgeted to twenty frames per arrival so a
 * long window cannot flood a log.</p>
 *
 * <p><b>Gated on Sodium being absent</b>, in {@code dungeontrain.vanillarenderer.mixins.json}: it
 * targets {@code setupRender}, which Sodium merges, and injecting into a merged method is a fatal
 * {@code InvalidInjectionException} rather than the quiet no-op {@code require = 0} suggests. See
 * {@code VanillaRendererMixinPlugin}.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererArrivalTraceMixin {

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Inject(method = "setupRender", at = @At("TAIL"), require = 0)
    private void dungeontrain$traceArrivalFrame(Camera camera, Frustum frustum,
                                                boolean hasCapturedFrustum, boolean isSpectator,
                                                CallbackInfo ci) {
        if (!ClientPortalSwap.inArrivalWindow()) return;
        boolean forced = PortalArrivalTrace.consumeForced();
        int listed = PortalArrivalTrace.consumeListed();
        String reasons = PortalArrivalTrace.consumeReasons();
        if (!PortalArrivalTrace.claimFrame()) return;

        // The three eases beside the list: a room drawn but black is a lift still climbing, and a
        // room hidden behind sky colour is a far plane still opening — neither of which the list
        // count can see.
        LogUtils.getLogger().info(
            "[DungeonTrain] Portal arrival frame: forced={} visible={} listed={} sealed={} camY={} "
                + "sky={} crossing={} fog={} cut={}..{} [{}]",
            forced, this.visibleSections.size(), listed, ClientPortalSeal.sealed(),
            String.format("%.1f", camera.getPosition().y),
            String.format("%.2f", ClientPortalRoomSky.applied()),
            String.format("%.2f", ClientPortalCrossing.current()),
            String.format("%.1f", ClientPortalRoomFog.current()),
            ClientPortalSeal.cut().floorY(), ClientPortalSeal.cut().roofY(), reasons);
    }
}
