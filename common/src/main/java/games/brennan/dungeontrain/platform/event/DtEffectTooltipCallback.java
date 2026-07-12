package games.brennan.dungeontrain.platform.event;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * Loader-neutral form of NeoForge's {@code GatherEffectScreenTooltipsEvent} (client
 * game bus). Fires while the tooltip for a status effect (in the inventory effect
 * list) is assembled. Not cancellable; the handler MUTATES the live {@code tooltip}
 * line list in place. Both parameters are vanilla.
 */
@FunctionalInterface
public interface DtEffectTooltipCallback {

    void onGatherEffectTooltips(MobEffectInstance effect, List<Component> tooltip);
}
