package games.brennan.dungeontrain.compat;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.registry.ModIngredientTypes;
import io.github.mortuusars.exposure_polaroid.world.item.InstantCameraItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.neoforged.neoforge.common.crafting.IngredientType;

import java.util.stream.Stream;

/**
 * A recipe ingredient that matches an unshot {@link DisposableCamera}. The camera is Exposure:
 * Polaroid's instant camera plus a custom-data marker, so neither a plain item ingredient (it would
 * take any instant camera) nor an exact {@code custom_data} match (any extra key would break it)
 * fits. A camera that has fired is mid-print and about to burn, so it can't be spent.
 *
 * <p>JSON: {@code {"type": "dungeontrain:disposable_camera"}}.</p>
 */
public final class DisposableCameraIngredient implements ICustomIngredient {

    public static final DisposableCameraIngredient INSTANCE = new DisposableCameraIngredient();
    public static final MapCodec<DisposableCameraIngredient> CODEC = MapCodec.unit(INSTANCE);

    private DisposableCameraIngredient() {}

    @Override
    public boolean test(ItemStack stack) {
        return stack != null && stack.getItem() instanceof InstantCameraItem && unspent(stack);
    }

    /** Carries the disposable-camera marker and has not taken its photo. */
    static boolean unspent(ItemStack stack) {
        return DisposableCamera.hasMarker(stack, DisposableCamera.NBT_CAMERA) && !DisposableCamera.isShot(stack);
    }

    /** What recipe viewers and the recipe book show in the slot. */
    @Override
    public Stream<ItemStack> getItems() {
        return Stream.of(DisposableCamera.create());
    }

    @Override
    public boolean isSimple() {
        return false;
    }

    @Override
    public IngredientType<?> getType() {
        return ModIngredientTypes.DISPOSABLE_CAMERA.get();
    }
}
