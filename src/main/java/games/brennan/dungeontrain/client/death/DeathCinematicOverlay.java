package games.brennan.dungeontrain.client.death;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * The black the death cinematic's shots cut through — full at each cut, and at the head and tail
 * of the sequence, so the shots dip rather than jump (see {@link DeathCinematic#blackAlpha}).
 *
 * <p>Drawn as a GUI layer rather than in the world so it covers everything the frame contains,
 * including the skip prompt, and needs no render-type juggling. Registered above all other layers
 * for the same reason.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DeathCinematicOverlay {

    private DeathCinematicOverlay() {}

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "death_cinematic_dip"),
            (graphics, deltaTracker) -> {
                if (!DeathCinematic.isActive()) return;
                float alpha = DeathCinematic.blackAlpha(deltaTracker.getGameTimeDeltaPartialTick(false));
                if (alpha <= 0.0f) return;
                int a = Math.min(255, Math.round(alpha * 255.0f));
                graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), a << 24);
            });
    }
}
