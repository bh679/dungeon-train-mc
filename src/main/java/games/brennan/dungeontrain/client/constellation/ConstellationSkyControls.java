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
 * the crosshair is the menu's hovered star: a click (either button) selects it, keeping it in the
 * details bar, and a second click on the selected star puts a point into it, which sits over the hotbar while a star is hovered or kept.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ConstellationSkyControls {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "constellation_details_bar");
    /** How long the bar takes to fade in or out. */
    static final long FADE_MS = 180;

    /** Has each button been let go since the last click that counted? */
    private static boolean attackReleased = true, useReleased = true;

    @SubscribeEvent
    public static void onTick(net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.keyAttack.isDown()) attackReleased = true;
        if (!mc.options.keyUse.isDown()) useReleased = true;
    }

    private static double barFade;
    private static long lastMs = -1;

    private ConstellationSkyControls() {}

    @SubscribeEvent
    public static void onClick(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || !ConstellationClient.patchOpen()) return;
        PerksScreen sky = ConstellationSky.screen();
        if (sky == null) return;
        if (!event.isUseItem() && !event.isAttack()) return;
        // Holding a button repeats the click every few ticks: only a fresh press counts, the repeats
        // are swallowed so a held button never selects and then activates.
        boolean fresh = event.isAttack() ? attackReleased : useReleased;
        String star = sky.hoveredPerk();
        if (star != null && !fresh) {
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
        }
        if (event.isAttack()) attackReleased = false;
        else useReleased = false;
        if (star == null) {
            // empty sky (nothing in the world under the crosshair) lets the kept star go
            if (event.isAttack() && (mc.hitResult == null || mc.hitResult.getType() == HitResult.Type.MISS)) sky.skySelect();
            return;
        }
        // Either button: the first click on a star selects it, a second click on it activates it.
        if (star.equals(sky.selectedPerk())) sky.skySpend();
        else sky.skySelect();
        event.setSwingHand(false);
        event.setCanceled(true);
    }

    /** Eating a cosmic apple turns the patch to hang over the horizon the player is facing. */
    @SubscribeEvent
    public static void onAteApple(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Finish event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getEntity() != mc.player || !event.getEntity().level().isClientSide()) return;
        if (!games.brennan.dungeontrain.item.CosmicAppleItem.isCosmicApple(event.getItem())) return;
        ConstellationGeometry.setHeading(mc.player.getYRot());
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
