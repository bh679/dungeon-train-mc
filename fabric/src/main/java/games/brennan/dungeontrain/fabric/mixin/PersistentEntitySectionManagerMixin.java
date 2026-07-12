package games.brennan.dungeontrain.fabric.mixin;

import games.brennan.dungeontrain.fabric.DtFire;
import games.brennan.dungeontrain.platform.event.DtEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gap-filler for {@code DtEvents.ENTITY_JOIN} (NeoForge {@code EntityJoinLevelEvent},
 * cancellable). Fabric's {@code ServerEntityEvents.ENTITY_LOAD} can't cancel and can't
 * distinguish fresh-vs-disk, so this hooks the server-side add path
 * {@code PersistentEntitySectionManager.addEntity(EntityAccess, boolean)} — where the
 * {@code existing} flag IS NeoForge's {@code loadedFromDisk} — firing ENTITY_JOIN in
 * registration order and cancelling (return {@code false}) on the first handler that
 * returns {@code true}, exactly like the NeoForge bridge. Server-side only (the client
 * uses a transient manager); DT's ENTITY_JOIN handlers self-filter to the server anyway.
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin {

    @Inject(method = "addEntity(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z",
            at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$entityJoin(EntityAccess entityAccess, boolean existing,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (!(entityAccess instanceof Entity entity)) {
            return;
        }
        if (DtEvents.ENTITY_JOIN.isEmpty()) {
            return;
        }
        if (DtFire.fireCancellable(DtEvents.ENTITY_JOIN.listeners(),
                cb -> cb.onEntityJoin(entity, entity.level(), existing))) {
            cir.setReturnValue(false); // first cancel wins — discards the entity
        }
    }
}
