package games.brennan.dungeontrain.mixin;

import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Reads a projectile's owner UUID ({@code event.LoyaltyTridentRecall}).
 *
 * <p>{@link Projectile#getOwner()} only finds the owner in the projectile's own dimension, so a
 * Loyalty trident left behind when its thrower went through a portal can't name them. The UUID can.</p>
 */
@Mixin(Projectile.class)
public interface ProjectileOwnerAccessor {

    @Nullable
    @Accessor("ownerUUID")
    UUID dungeontrain$getOwnerUUID();
}
