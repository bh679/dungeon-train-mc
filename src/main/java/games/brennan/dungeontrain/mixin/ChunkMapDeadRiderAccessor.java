package games.brennan.dungeontrain.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapDeadRiderAccessor {

    @Invoker("removeEntity")
    void dungeontrain$removeEntity(Entity entity);
}
