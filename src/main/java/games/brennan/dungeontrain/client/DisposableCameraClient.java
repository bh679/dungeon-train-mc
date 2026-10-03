package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.DisposableCamera;
import io.github.mortuusars.exposure_polaroid.ExposurePolaroid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.item.ItemPropertyFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;

/**
 * The disposable camera's print and reload visuals, per camera.
 *
 * <p>Polaroid drives its {@code printing} model predicate from the item cooldown, and vanilla draws
 * the cooldown sweep over every slot of the item — but item cooldowns belong to the item, so one shot
 * set every disposable camera printing and reloading. DT lifts that cooldown once the viewfinder
 * closes (see {@code DisposableCameraEvents}) and draws both from the shooting camera's own timer
 * instead: the {@code printing} predicate reads {@link DisposableCamera#printProgress}, and an item
 * decorator draws vanilla's sweep from {@link DisposableCamera#reloadRemaining} on that stack alone.
 * Any other instant camera keeps Polaroid's predicate.</p>
 *
 * <p>{@link #registerPrintingPredicate} runs from client setup's {@code enqueueWork}; DT loads after
 * Polaroid, so Polaroid's predicate is already registered and is replaced here.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class DisposableCameraClient {

    /** Vanilla's cooldown overlay colour (see {@code GuiGraphics#renderItemDecorations}). */
    private static final int SWEEP_COLOR = Integer.MAX_VALUE;
    private static final int SLOT_SIZE = 16;

    private DisposableCameraClient() {}

    public static void registerPrintingPredicate() {
        Item camera = ExposurePolaroid.Items.INSTANT_CAMERA.get();
        ResourceLocation printing = ExposurePolaroid.resource("printing");
        ItemPropertyFunction polaroid = ItemProperties.getProperty(new ItemStack(camera), printing);
        ItemProperties.register(camera, printing, (stack, level, entity, seed) -> {
            if (DisposableCamera.is(stack)) {
                return DisposableCamera.printProgress(stack, clientGameTime());
            }
            return polaroid != null ? polaroid.call(stack, level, entity, seed) : 0f;
        });
    }

    @SubscribeEvent
    public static void registerDecorations(RegisterItemDecorationsEvent event) {
        event.register(ExposurePolaroid.Items.INSTANT_CAMERA.get(), DisposableCameraClient::renderReloadSweep);
    }

    /** Vanilla's cooldown sweep, drawn the same way, on the shooting camera only. */
    private static boolean renderReloadSweep(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
        if (!DisposableCamera.is(stack)) {
            return false;
        }
        float remaining = DisposableCamera.reloadRemaining(stack, clientGameTime());
        if (remaining <= 0f) {
            return false;
        }
        int top = y + Mth.floor(SLOT_SIZE * (1.0f - remaining));
        int bottom = top + Mth.ceil(SLOT_SIZE * remaining);
        graphics.fill(RenderType.guiOverlay(), x, top, x + SLOT_SIZE, bottom, SWEEP_COLOR);
        return true;
    }

    /** Client game time plus the frame's partial tick, for smooth animation; 0 with no level. */
    private static double clientGameTime() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return 0.0;
        }
        return level.getGameTime() + minecraft.getTimer().getGameTimeDeltaPartialTick(false);
    }
}
