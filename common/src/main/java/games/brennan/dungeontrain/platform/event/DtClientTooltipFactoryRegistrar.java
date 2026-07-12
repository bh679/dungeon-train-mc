package games.brennan.dungeontrain.platform.event;

import java.util.function.Function;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

/**
 * Loader-neutral registrar handed to a
 * {@link DtClientTooltipComponentFactoryRegistrationCallback}. Maps a custom
 * {@link TooltipComponent} data type to the {@link ClientTooltipComponent} factory
 * that renders it — the same mapping NeoForge's
 * {@code RegisterClientTooltipComponentFactoriesEvent.register} records. Both types
 * are vanilla and available in {@code :common}; a Fabric bridge backs this with
 * {@code TooltipComponentCallback} in a later stage.
 */
public interface DtClientTooltipFactoryRegistrar {

    <T extends TooltipComponent> void register(Class<T> type,
                                               Function<? super T, ? extends ClientTooltipComponent> factory);
}
