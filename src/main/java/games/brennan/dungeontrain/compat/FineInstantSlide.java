package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * The fine instant colour slide — Exposure: Polaroid's colour slide at the original camera's
 * resolution.
 *
 * <p>Not an item of DT's own: Exposure reads a film's photo size from the stack's
 * {@code exposure:film_frame_size} component, so this is the add-on's slide carrying that component and
 * a DT name. The crafting recipe ({@code data/dungeontrain/recipe/fine_instant_color_slide.json}) builds
 * the same stack; this class only puts it in the creative menu. Both mods are named by id — nothing
 * here links against them.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class FineInstantSlide {

    /** The original Exposure camera's photo size; the instant camera's default is 240. */
    public static final int FRAME_SIZE = 320;

    static final ResourceLocation SLIDE_ITEM =
        ResourceLocation.fromNamespaceAndPath("exposure_polaroid", "instant_color_slide");
    static final ResourceLocation FRAME_SIZE_COMPONENT =
        ResourceLocation.fromNamespaceAndPath("exposure", "film_frame_size");
    static final String NAME_KEY = "item.dungeontrain.fine_instant_color_slide";

    private FineInstantSlide() {}

    @SubscribeEvent
    public static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() != CreativeModeTabs.TOOLS_AND_UTILITIES) {
            return;
        }
        ItemStack stack = create();
        if (!stack.isEmpty()) {
            event.accept(stack, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        }
    }

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
        stack.set(DataComponents.ITEM_NAME, Component.translatable(NAME_KEY));
        return stack;
    }
}
