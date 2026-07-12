package games.brennan.dungeontrain.fabric.mixin;

import games.brennan.dungeontrain.platform.event.DtEntityTickCallback;
import games.brennan.dungeontrain.platform.event.DtEvents;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gap-filler for {@code DtEvents.ENTITY_TICK} (NeoForge {@code EntityTickEvent.Pre}).
 * Fabric has no entity-tick event; fires at the HEAD of {@code Entity.tick}. DT's sole
 * handler never cancels, so this is a plain observe (no cancellation).
 */
@Mixin(Entity.class)
public abstract class EntityTickMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void dungeonTrain$entityTickPre(CallbackInfo ci) {
        if (DtEvents.ENTITY_TICK.isEmpty()) {
            return;
        }
        Entity self = (Entity) (Object) this;
        for (DtEntityTickCallback cb : DtEvents.ENTITY_TICK.listeners()) {
            cb.onEntityTick(self);
        }
    }
}
