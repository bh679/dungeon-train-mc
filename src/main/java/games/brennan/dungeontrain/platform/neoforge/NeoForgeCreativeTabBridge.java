package games.brennan.dungeontrain.platform.neoforge;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.platform.event.DtBuildCreativeTabContentsCallback;
import games.brennan.dungeontrain.platform.event.DtEvents;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for {@code BuildCreativeModeTabContentsEvent}.
 * Not {@code Dist.CLIENT}-gated — matches the original {@code ModItems} /
 * {@code ModBlocks} {@code @EventBusSubscriber(modid = ...)} handlers, which
 * ran on both logical sides.
 */
@EventBusSubscriber(modid = DtCore.MOD_ID)
public final class NeoForgeCreativeTabBridge {

    private NeoForgeCreativeTabBridge() {}

    @SubscribeEvent
    public static void onBuildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        for (DtBuildCreativeTabContentsCallback cb : DtEvents.BUILD_CREATIVE_TAB_CONTENTS.listeners()) {
            cb.onBuildCreativeTabContents(event.getTabKey(), event::accept);
        }
    }
}
