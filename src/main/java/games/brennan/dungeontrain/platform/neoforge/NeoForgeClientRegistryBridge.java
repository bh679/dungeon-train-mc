package games.brennan.dungeontrain.platform.neoforge;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.platform.event.DtClientTooltipComponentFactoryRegistrationCallback;
import games.brennan.dungeontrain.platform.event.DtClientTooltipFactoryRegistrar;
import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtGuiLayerRegistrar;
import games.brennan.dungeontrain.platform.event.DtGuiLayerRegistrationCallback;
import games.brennan.dungeontrain.platform.event.DtKeyMappingRegistrationCallback;
import games.brennan.dungeontrain.platform.event.DtReloadListenerRegistrationCallback;
import java.util.function.Function;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for the mod-bus register-style client
 * events (key mappings, GUI layers, client tooltip-component factories). All three
 * are {@code IModBusEvent}s; NeoForge 1.21.1 auto-routes each {@code @SubscribeEvent}
 * method to the mod bus by event type (the {@code bus} attribute is deprecated), so
 * a plain {@code value = Dist.CLIENT} subscriber receives them — exactly as the
 * original handlers did (e.g. {@code CinematographerHotkeyClient.onRegister}). Each
 * handler is fed a thin registrar that delegates to the real NeoForge event.
 */
@EventBusSubscriber(modid = DtCore.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeClientRegistryBridge {

    private NeoForgeClientRegistryBridge() {}

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        for (DtKeyMappingRegistrationCallback cb : DtEvents.KEY_MAPPING_REGISTRATION.listeners()) {
            cb.registerKeyMappings(event::register);
        }
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        DtGuiLayerRegistrar registrar = new DtGuiLayerRegistrar() {
            @Override public void registerAboveAll(ResourceLocation id, LayeredDraw.Layer layer) {
                event.registerAboveAll(id, layer);
            }
            @Override public void registerBelowAll(ResourceLocation id, LayeredDraw.Layer layer) {
                event.registerBelowAll(id, layer);
            }
            @Override public void registerAbove(ResourceLocation vanillaLayer, ResourceLocation id, LayeredDraw.Layer layer) {
                event.registerAbove(vanillaLayer, id, layer);
            }
            @Override public void registerBelow(ResourceLocation vanillaLayer, ResourceLocation id, LayeredDraw.Layer layer) {
                event.registerBelow(vanillaLayer, id, layer);
            }
        };
        for (DtGuiLayerRegistrationCallback cb : DtEvents.GUI_LAYER_REGISTRATION.listeners()) {
            cb.registerGuiLayers(registrar);
        }
    }

    @SubscribeEvent
    public static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        for (DtReloadListenerRegistrationCallback cb :
                DtEvents.CLIENT_RELOAD_LISTENER_REGISTRATION.listeners()) {
            cb.registerReloadListeners(event::registerReloadListener);
        }
    }

    @SubscribeEvent
    public static void onRegisterTooltipFactories(RegisterClientTooltipComponentFactoriesEvent event) {
        DtClientTooltipFactoryRegistrar registrar = new DtClientTooltipFactoryRegistrar() {
            @Override public <T extends TooltipComponent> void register(
                    Class<T> type, Function<? super T, ? extends ClientTooltipComponent> factory) {
                event.register(type, factory::apply);
            }
        };
        for (DtClientTooltipComponentFactoryRegistrationCallback cb :
                DtEvents.CLIENT_TOOLTIP_FACTORY_REGISTRATION.listeners()) {
            cb.registerFactories(registrar);
        }
    }
}
