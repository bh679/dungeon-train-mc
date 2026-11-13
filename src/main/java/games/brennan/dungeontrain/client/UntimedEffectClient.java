package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.IClientMobEffectExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.List;

/**
 * Effects that last exactly as long as their condition — Drifting (inside a drifting carriage) and
 * Live (streaming with the Live Eye) — have no duration worth showing, so their inventory row draws
 * the name alone, centred where vanilla stacks name and duration. Their tooltip is
 * {@link UntimedEffectTooltip}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class UntimedEffectClient {

    private static final List<DeferredHolder<MobEffect, ? extends MobEffect>> UNTIMED =
        List.of(ModMobEffects.DRIFTING, ModMobEffects.LIVE);

    /** Vanilla's label inset from the row's left edge (past the icon). */
    private static final int TEXT_X = 28;
    /** Vertically centres one line in the 32-pixel row. */
    private static final int TEXT_Y = 12;
    private static final int WHITE = 0xFFFFFF;

    private UntimedEffectClient() {}

    /** Whether {@code effect} is drawn without a duration. */
    static boolean isUntimed(Holder<MobEffect> effect) {
        for (DeferredHolder<MobEffect, ? extends MobEffect> holder : UNTIMED) {
            if (effect.is(holder.getId())) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        IClientMobEffectExtensions nameOnly = new IClientMobEffectExtensions() {
            @Override
            public boolean renderInventoryText(MobEffectInstance instance, EffectRenderingInventoryScreen<?> screen,
                                               GuiGraphics guiGraphics, int x, int y, int blitOffset) {
                Component name = instance.getEffect().value().getDisplayName();
                guiGraphics.drawString(Minecraft.getInstance().font, name, x + TEXT_X, y + TEXT_Y, WHITE);
                return true;
            }
        };
        event.registerMobEffect(nameOnly, UNTIMED.stream().map(DeferredHolder::get).toArray(MobEffect[]::new));
    }
}
