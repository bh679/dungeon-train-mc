package games.brennan.dungeontrain.platform.neoforge;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtRenderLevelAfterTranslucentCallback;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for world rendering. Client-only so it
 * never loads on a dedicated server. Owns the stage dispatch: it fires
 * {@code RENDER_LEVEL_AFTER_TRANSLUCENT} only at {@code AFTER_TRANSLUCENT_BLOCKS}
 * (the sole stage all 12 DT handlers used), so the converted handlers no longer
 * carry their own stage guard — a Fabric bridge wires the same field straight to
 * {@code WorldRenderEvents.AFTER_TRANSLUCENT}.
 */
@EventBusSubscriber(modid = DtCore.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeClientRenderLevelBridge {

    private NeoForgeClientRenderLevelBridge() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        for (DtRenderLevelAfterTranslucentCallback cb : DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT.listeners()) {
            cb.onRenderLevel(event.getPoseStack(), event.getCamera(), event.getPartialTick());
        }
    }
}
