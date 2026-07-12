package games.brennan.dungeontrain.mixin.client;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Read-only accessor on {@link AdvancementsScreen}'s private {@code tabs}
 * map so {@code DefaultAdvancementsTab} can look up our root tab by
 * {@link AdvancementHolder} and call
 * {@link AdvancementsScreen#onSelectedTabChanged} on its holder.
 *
 * <p>The vanilla field is
 * {@code Map<AdvancementHolder, AdvancementTab>} — typed that way here so
 * Mixin's refmap binds correctly.</p>
 */
@Mixin(AdvancementsScreen.class)
public interface AdvancementsScreenAccessor {

    @Accessor("tabs")
    Map<AdvancementHolder, AdvancementTab> dungeontrain$getTabs();
}
