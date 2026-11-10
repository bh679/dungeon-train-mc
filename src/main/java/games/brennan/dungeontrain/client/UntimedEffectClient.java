package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.IClientMobEffectExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * Drifting has no duration worth showing — it lasts exactly as long as the player stays inside — so
 * the inventory row draws the effect's name alone, centred where vanilla stacks name and duration.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DriftingEffectClient {

    /** Vanilla's label inset from the row's left edge (past the icon). */
    private static final int TEXT_X = 28;
    /** Vertically centres one line in the 32-pixel row. */
    private static final int TEXT_Y = 12;
    private static final int WHITE = 0xFFFFFF;

    private DriftingEffectClient() {}

    @SubscribeEvent
    public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerMobEffect(new IClientMobEffectExtensions() {
            @Override
            public boolean renderInventoryText(MobEffectInstance instance, EffectRenderingInventoryScreen<?> screen,
                                               GuiGraphics guiGraphics, int x, int y, int blitOffset) {
                Component name = instance.getEffect().value().getDisplayName();
                guiGraphics.drawString(Minecraft.getInstance().font, name, x + TEXT_X, y + TEXT_Y, WHITE);
                return true;
            }
        }, ModMobEffects.DRIFTING.get());
    }
}
