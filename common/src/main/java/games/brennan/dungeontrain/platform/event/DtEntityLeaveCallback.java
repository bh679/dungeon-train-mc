package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Loader-neutral form of NeoForge's {@code EntityLeaveLevelEvent}: fired when an
 * entity is removed from a level (client and server; handlers self-filter). Not
 * cancellable; read-only. DT's single handler was NORMAL priority.
 *
 * @param entity the leaving entity (matches {@code getEntity()})
 * @param level  the level it left (matches {@code getLevel()})
 */
@FunctionalInterface
public interface DtEntityLeaveCallback {
    void onEntityLeave(Entity entity, Level level);
}
