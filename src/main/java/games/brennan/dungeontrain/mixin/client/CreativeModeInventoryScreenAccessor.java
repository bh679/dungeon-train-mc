package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Mixin accessor for {@link CreativeModeInventoryScreen}'s private members.
 * Used by the side-tab buttons to switch into our prefab tabs via vanilla's
 * own {@code selectTab} path (so vanilla handles the items grid, title,
 * scrollbar, tooltips, etc.) and to read the current {@code selectedTab}
 * for active-state highlight.
 */
@Mixin(CreativeModeInventoryScreen.class)
public interface CreativeModeInventoryScreenAccessor {

    @Invoker("selectTab")
    void dungeontrain$invokeSelectTab(CreativeModeTab tab);

    @Accessor("selectedTab")
    static CreativeModeTab dungeontrain$getSelectedTab() {
        throw new AssertionError("Mixin accessor not transformed");
    }
}
