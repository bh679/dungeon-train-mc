package games.brennan.dungeontrain.compat.jei;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.DisabledModContent;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IExtraIngredientRegistration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists {@link DisabledModContent#JEI_EXTRA_ITEMS} in JEI. DT hides the biome mods' creative tabs, and JEI builds
 * its item list from the tabs, so those items would otherwise be missing from it. Loaded only by JEI's own
 * annotation scan — nothing references this class when JEI is not installed.
 */
@JeiPlugin
public final class DungeonTrainJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "jei");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerExtraIngredients(IExtraIngredientRegistration registration) {
        // Either creative setting on already lets the hidden tabs' contents reach JEI; adding them again would list them twice.
        if (ClientDisplayConfig.isCreativeModBlockTabs() || ClientDisplayConfig.isCreativeModBlocksInSearch()) {
            return;
        }
        List<ItemStack> extras = new ArrayList<>();
        for (String id : DisabledModContent.JEI_EXTRA_ITEMS) {
            BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id))
                .map(ItemStack::new)
                .ifPresent(extras::add);
        }
        registration.addExtraItemStacks(extras);
    }
}
