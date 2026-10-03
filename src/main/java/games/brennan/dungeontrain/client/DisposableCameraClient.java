package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.compat.DisposableCamera;
import io.github.mortuusars.exposure_polaroid.ExposurePolaroid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.item.ItemPropertyFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The disposable camera's print animation, per camera.
 *
 * <p>Polaroid drives its {@code printing} model predicate from the item cooldown, and item cooldowns
 * belong to the item — so one shot set every disposable camera in the inventory printing. DT replaces
 * that predicate: a disposable camera reads its own print timer
 * ({@link DisposableCamera#printProgress}); any other instant camera keeps Polaroid's behaviour.</p>
 *
 * <p>Runs from client setup's {@code enqueueWork}; DT loads after Polaroid, so Polaroid's predicate is
 * already registered and is replaced here.</p>
 */
public final class DisposableCameraClient {

    private DisposableCameraClient() {}

    public static void registerPrintingPredicate() {
        Item camera = ExposurePolaroid.Items.INSTANT_CAMERA.get();
        ResourceLocation printing = ExposurePolaroid.resource("printing");
        ItemPropertyFunction polaroid = ItemProperties.getProperty(new ItemStack(camera), printing);
        ItemProperties.register(camera, printing, (stack, level, entity, seed) -> {
            if (DisposableCamera.is(stack)) {
                return printProgress(stack);
            }
            return polaroid != null ? polaroid.call(stack, level, entity, seed) : 0f;
        });
    }

    private static float printProgress(ItemStack stack) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return 0f;
        }
        double now = level.getGameTime() + minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        return DisposableCamera.printProgress(stack, now);
    }
}
