package games.brennan.dungeontrain.compat;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The fine instant colour slide — Exposure: Polaroid's colour slide at the original camera's
 * resolution.
 *
 * <p>Not an item of DT's own: Exposure reads a film's photo size from the stack's
 * {@code exposure:film_frame_size} component, so this is the add-on's slide carrying that component.
 * Players never hold one: it is what a {@link DisposableCamera} comes loaded with. Both mods are
 * named by id — nothing here links against them.</p>
 */
public final class FineInstantSlide {

    /** The original Exposure camera's photo size; the instant camera's default is 240. */
    public static final int FRAME_SIZE = 320;

    static final ResourceLocation SLIDE_ITEM =
        ResourceLocation.fromNamespaceAndPath("exposure_polaroid", "instant_color_slide");
    static final ResourceLocation FRAME_SIZE_COMPONENT =
        ResourceLocation.fromNamespaceAndPath("exposure", "film_frame_size");

    private FineInstantSlide() {}

    /** A fine slide, or an empty stack if Exposure's item or component isn't registered. */
    @SuppressWarnings("unchecked")
    public static ItemStack create() {
        Item item = BuiltInRegistries.ITEM.get(SLIDE_ITEM);
        DataComponentType<?> frameSize = BuiltInRegistries.DATA_COMPONENT_TYPE.get(FRAME_SIZE_COMPONENT);
        if (item == Items.AIR || frameSize == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item);
        stack.set((DataComponentType<Integer>) frameSize, FRAME_SIZE);
        return stack;
    }
}
