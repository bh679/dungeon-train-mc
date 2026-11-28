package games.brennan.dungeontrain.client.constellation;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.perks.PerksScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Spending from the sky. Standing in a constellation block with the patch open, the perk star under
 * the crosshair is the menu's hovered star: right-click puts a point into it, left-click keeps it in
 * the details bar, which sits over the hotbar while a star is hovered or kept.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ConstellationSkyControls {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "constellation_details_bar");
    /** How long the bar takes to fade in or out. */
    static final long FADE_MS = 180;

    private static double barFade;
    private static long lastMs = -1;

    private ConstellationSkyControls() {}

    @SubscribeEvent
    public static void onClick(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || !ConstellationClient.patchOpen()) return;
        PerksScreen sky = ConstellationSky.screen();
        if (sky == null) return;
        if (event.isUseItem()) {
            if (!sky.skySpend()) return;
        } else if (event.isAttack()) {
            boolean onStar = sky.hoveredPerk() != null;
            // empty sky (nothing in the world under the crosshair) lets the kept star go
            if (!onStar && (mc.hitResult == null || mc.hitResult.getType() == HitResult.Type.MISS)) sky.skySelect();
            if (!onStar) return;
            sky.skySelect();
        } else {
            return;
        }
        event.setSwingHand(false);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.EXPERIENCE_LEVEL, LAYER, ConstellationSkyControls::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        PerksScreen sky = ConstellationSky.screen();
        boolean open = ConstellationClient.patchOpen();
        if (!open && sky != null) sky.skyDeselect(); // stepping out lets the kept star go
        boolean show = open && mc.screen == null && sky != null && sky.skyBarHasPerk();
        long now = net.minecraft.Util.getMillis();
        long dt = lastMs < 0 ? 0 : Math.min(250, now - lastMs);
        lastMs = now;
        barFade = step(barFade, show, dt);
        if (barFade <= 0.0 || sky == null || mc.options.hideGui) return;
        sky.renderSkyBar(g, g.guiWidth(), g.guiHeight(), barFade, delta.getGameTimeDeltaPartialTick(false));
    }

    /** The bar's fade, one frame on: toward 1 while shown, toward 0 after. */
    static double step(double fade, boolean show, long dtMs) {
        double d = dtMs / (double) FADE_MS;
        return show ? Math.min(1.0, fade + d) : Math.max(0.0, fade - d);
    }
}
