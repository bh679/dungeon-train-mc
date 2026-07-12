package games.brennan.dungeontrain.platform.event;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.world.entity.Entity;

/**
 * Loader-neutral form of NeoForge's {@code AdvancementEvent.AdvancementEarnEvent}
 * — fired on the server thread when an entity earns an advancement (including
 * mods' and vanilla's, and re-fired when a listener grants further advancements).
 * Not cancellable; parameters are read-only. The bridge invokes every listener
 * in registration order.
 *
 * @param entity      the entity that earned the advancement (guard for {@code ServerPlayer})
 * @param advancement the earned advancement
 */
@FunctionalInterface
public interface DtAdvancementEarnCallback {

    void onAdvancementEarn(Entity entity, AdvancementHolder advancement);
}
