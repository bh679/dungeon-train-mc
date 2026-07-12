package games.brennan.dungeontrain.fabric.event;

import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.dungeontrain.fabric.mixin.client.ScreenInvoker;
import games.brennan.dungeontrain.platform.event.DtClientLoggingCallback;
import games.brennan.dungeontrain.platform.event.DtClientTickCallback;
import games.brennan.dungeontrain.platform.event.DtClientTooltipComponentFactoryRegistrationCallback;
import games.brennan.dungeontrain.platform.event.DtClientTooltipFactoryRegistrar;
import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtGuiLayerRegistrar;
import games.brennan.dungeontrain.platform.event.DtGuiLayerRegistrationCallback;
import games.brennan.dungeontrain.platform.event.DtItemTooltipCallback;
import games.brennan.dungeontrain.platform.event.DtKeyMappingRegistrationCallback;
import games.brennan.dungeontrain.platform.event.DtLeftClickBlockCallback;
import games.brennan.dungeontrain.platform.event.DtPriority;
import games.brennan.dungeontrain.platform.event.DtScreenClosingCallback;
import games.brennan.dungeontrain.platform.event.DtScreenInit;
import games.brennan.dungeontrain.platform.event.DtScreenInitCallback;
import games.brennan.dungeontrain.platform.event.DtScreenRenderPostCallback;
import games.brennan.dungeontrain.platform.event.DtScreenRenderPreCallback;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.TooltipComponentCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

/**
 * Client counterpart to {@link FabricServerEventBridges}: subscribes the real Fabric
 * client-side events and fires the matching {@code DtEvents} fields. Invoked only from
 * the client entrypoint. Register-style events (key mappings, GUI layers, tooltip-factory,
 * client reload listeners) are fed a thin registrar that delegates to the Fabric API.
 *
 * <p><b>Fabric-v1 gaps (not wired — documented):</b> {@code CLIENT_COMMAND_REGISTRATION}
 * (Fabric client commands use {@code FabricClientCommandSource}, not {@code CommandSourceStack}),
 * {@code FOG_COLOR}, {@code EFFECT_TOOLTIP}, {@code GATHER_TOOLTIP_COMPONENTS},
 * {@code INTERACTION_KEY} (no Fabric equivalent for the attack/use key-mapping-triggered
 * hook). {@code SELECT_MUSIC}, {@code RENDER_HAND}, {@code KEY_INPUT}, {@code MOUSE_BUTTON_PRE},
 * {@code MOUSE_SCROLL} and {@code SCREEN_OPENING} are fired from client gap-filler mixins.</p>
 */
public final class FabricClientEventBridges {

    private FabricClientEventBridges() {}

    public static void register() {
        registerTicksAndConnection();
        registerWorldRenderAndTooltip();
        registerScreens();
        registerInput();
        registerDeclarative();
    }

    private static void registerTicksAndConnection() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            for (DtClientTickCallback cb : DtEvents.CLIENT_TICK_PRE.listeners()) {
                cb.onClientTick();
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            for (DtClientTickCallback cb : DtEvents.CLIENT_TICK_POST.listeners()) {
                cb.onClientTick();
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            for (DtClientLoggingCallback cb : DtEvents.CLIENT_LOGGING_IN.listeners()) {
                cb.onClientLogging();
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            for (DtClientLoggingCallback cb : DtEvents.CLIENT_LOGGING_OUT.listeners()) {
                cb.onClientLogging();
            }
        });
        ClientSendMessageEvents.CHAT.register(message -> {
            DtEvents.CLIENT_CHAT.listeners().forEach(cb -> cb.onClientChat());
        });
    }

    private static void registerWorldRenderAndTooltip() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            if (DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT.isEmpty()) {
                return;
            }
            PoseStack poseStack = context.matrixStack() != null ? context.matrixStack() : new PoseStack();
            DeltaTracker delta = context.tickCounter();
            DtEvents.RENDER_LEVEL_AFTER_TRANSLUCENT.listeners()
                .forEach(cb -> cb.onRenderLevel(poseStack, context.camera(), delta));
        });
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            for (DtItemTooltipCallback cb : DtEvents.ITEM_TOOLTIP.listeners()) {
                cb.onItemTooltip(stack, lines);
            }
        });
    }

    private static void registerScreens() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            fireScreenInit(screen);
            ScreenEvents.beforeRender(screen).register((scr, graphics, mouseX, mouseY, tickDelta) -> {
                for (DtScreenRenderPreCallback cb : DtEvents.SCREEN_RENDER_PRE.listeners()) {
                    cb.onScreenRenderPre(scr);
                }
            });
            ScreenEvents.afterRender(screen).register((scr, graphics, mouseX, mouseY, tickDelta) -> {
                for (DtScreenRenderPostCallback cb : DtEvents.SCREEN_RENDER_POST.listeners()) {
                    cb.onScreenRenderPost(scr, graphics);
                }
            });
            ScreenEvents.remove(screen).register(scr -> {
                for (DtScreenClosingCallback cb : DtEvents.SCREEN_CLOSING.listeners()) {
                    cb.onScreenClosing(scr);
                }
            });
        });
    }

    @SuppressWarnings("unchecked")
    private static void fireScreenInit(Screen screen) {
        DtScreenInit carrier = new DtScreenInit() {
            @Override public Screen getScreen() { return screen; }
            @Override public List<GuiEventListener> getListenersList() {
                return (List<GuiEventListener>) screen.children();
            }
            @Override public <T extends GuiEventListener & Renderable & NarratableEntry> void addListener(T listener) {
                ((ScreenInvoker) screen).dungeonTrain$addRenderableWidget(listener);
            }
        };
        for (DtScreenInitCallback cb : DtEvents.SCREEN_INIT_POST.listeners()) {
            cb.onScreenInit(carrier);
        }
    }

    private static void registerInput() {
        // LeftClickBlock — two tiers (HIGHEST menu cancellers, NORMAL observer). Return FAIL to cancel.
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            for (DtLeftClickBlockCallback cb : DtEvents.LEFT_CLICK_BLOCK.listeners(DtPriority.HIGHEST)) {
                if (cb.onLeftClickBlock(world, pos)) {
                    return InteractionResult.FAIL;
                }
            }
            for (DtLeftClickBlockCallback cb : DtEvents.LEFT_CLICK_BLOCK.listeners(DtPriority.NORMAL)) {
                if (cb.onLeftClickBlock(world, pos)) {
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });
    }

    private static void registerDeclarative() {
        // Key mappings.
        for (DtKeyMappingRegistrationCallback cb : DtEvents.KEY_MAPPING_REGISTRATION.listeners()) {
            cb.registerKeyMappings(KeyBindingHelper::registerKeyBinding);
        }

        // GUI HUD layers — collect the registered "above all" layers in order, render them
        // in one HudRenderCallback (registration order == render order, as with NeoForge).
        List<LayeredDraw.Layer> hudLayers = new ArrayList<>();
        DtGuiLayerRegistrar guiRegistrar = new DtGuiLayerRegistrar() {
            @Override public void registerAboveAll(ResourceLocation id, LayeredDraw.Layer layer) { hudLayers.add(layer); }
            @Override public void registerBelowAll(ResourceLocation id, LayeredDraw.Layer layer) { hudLayers.add(layer); }
            @Override public void registerAbove(ResourceLocation vanillaLayer, ResourceLocation id, LayeredDraw.Layer layer) { hudLayers.add(layer); }
            @Override public void registerBelow(ResourceLocation vanillaLayer, ResourceLocation id, LayeredDraw.Layer layer) { hudLayers.add(layer); }
        };
        for (DtGuiLayerRegistrationCallback cb : DtEvents.GUI_LAYER_REGISTRATION.listeners()) {
            cb.registerGuiLayers(guiRegistrar);
        }
        if (!hudLayers.isEmpty()) {
            HudRenderCallback.EVENT.register((graphics, tickCounter) -> {
                for (LayeredDraw.Layer layer : hudLayers) {
                    layer.render(graphics, (DeltaTracker) tickCounter);
                }
            });
        }

        // Client tooltip-component factories — one callback that maps data type → client component.
        List<TypedFactory<?>> factories = new ArrayList<>();
        DtClientTooltipFactoryRegistrar tooltipRegistrar = new DtClientTooltipFactoryRegistrar() {
            @Override public <T extends TooltipComponent> void register(
                    Class<T> type, Function<? super T, ? extends ClientTooltipComponent> factory) {
                factories.add(new TypedFactory<>(type, factory));
            }
        };
        for (DtClientTooltipComponentFactoryRegistrationCallback cb : DtEvents.CLIENT_TOOLTIP_FACTORY_REGISTRATION.listeners()) {
            cb.registerFactories(tooltipRegistrar);
        }
        if (!factories.isEmpty()) {
            TooltipComponentCallback.EVENT.register(data -> {
                for (TypedFactory<?> tf : factories) {
                    ClientTooltipComponent c = tf.tryApply(data);
                    if (c != null) {
                        return c;
                    }
                }
                return null;
            });
        }

        // Client resource reload listeners.
        int[] counter = {0};
        DtEvents.CLIENT_RELOAD_LISTENER_REGISTRATION.listeners().forEach(cb ->
            cb.registerReloadListeners(listener -> {
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    "dungeontrain", "client_reload_" + (counter[0]++));
                IdentifiableResourceReloadListener wrapped = FabricServerEventBridges.wrapReload(id, listener);
                ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(wrapped);
            }));
    }

    private record TypedFactory<T extends TooltipComponent>(
            Class<T> type, Function<? super T, ? extends ClientTooltipComponent> factory) {
        ClientTooltipComponent tryApply(TooltipComponent data) {
            return type.isInstance(data) ? factory.apply(type.cast(data)) : null;
        }
    }
}
