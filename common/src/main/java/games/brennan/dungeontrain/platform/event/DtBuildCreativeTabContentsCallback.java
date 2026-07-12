package games.brennan.dungeontrain.platform.event;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

/**
 * Loader-neutral form of NeoForge's mod-bus {@code BuildCreativeModeTabContentsEvent}:
 * fires once per registered {@link CreativeModeTab} (both vanilla and mod tabs)
 * as the Creative inventory's contents are built. Declarative — each listener
 * checks {@code tabKey} against the tab(s) it cares about and, if it matches,
 * feeds stacks to {@code output}. Not cancellable; independent (order among
 * listeners irrelevant, matching every DT handler converted so far).
 */
@FunctionalInterface
public interface DtBuildCreativeTabContentsCallback {

    void onBuildCreativeTabContents(ResourceKey<CreativeModeTab> tabKey, Consumer<ItemStack> output);
}
