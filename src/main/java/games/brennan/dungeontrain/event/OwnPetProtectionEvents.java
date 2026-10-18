package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A player can't hurt their own pet.
 *
 * <p>Vanilla lets you punch, sweep or shoot your own tamed wolf or horse, and on a
 * cramped carriage that mostly happens by accident: a sweep while fighting beside your dog, an
 * arrow through your cat. Two hooks close it:</p>
 * <ul>
 *   <li>{@link AttackEntityEvent} stops the melee swing itself, so there is no knockback, no crit
 *   sound and no weapon wear either.</li>
 *   <li>{@link LivingIncomingDamageEvent} catches the rest of what the owner <i>causes</i>: sweep
 *   hits, arrows and tridents, thrown potions ({@code source.getEntity()} is the shooter, not the
 *   projectile). Explosions are let through on purpose: TNT the owner lit still hurts the pet.</li>
 * </ul>
 *
 * <p>"Pet" is any {@link OwnableEntity} ({@code TamableAnimal} and {@code AbstractHorse}). Other
 * players, mobs and the world can still hurt it.</p>
 *
 * <p>On by default; each player can switch it off for themselves in Options &gt; Dungeon Train &gt;
 * General ({@link PetProtectionMirror}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class OwnPetProtectionEvents {

    private OwnPetProtectionEvents() {}

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (isOwnersPet(event.getEntity(), event.getTarget())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        // Explosions still count: TNT you lit next to your dog is on you.
        if (event.getSource().is(DamageTypeTags.IS_EXPLOSION)) return;
        if (isOwnersPet(event.getSource().getEntity(), event.getEntity())) event.setCanceled(true);
    }

    private static boolean isOwnersPet(@Nullable Entity attacker, Entity target) {
        if (!(attacker instanceof Player player)) return false;
        if (!(target instanceof OwnableEntity pet)) return false;
        if (!PetProtectionMirror.isEnabled(player)) return false;
        return isOwnPet(player.getUUID(), pet.getOwnerUUID());
    }

    /** True when {@code owner} is {@code attacker} — a wild animal ({@code owner == null}) never is. */
    static boolean isOwnPet(UUID attacker, @Nullable UUID owner) {
        return owner != null && owner.equals(attacker);
    }
}
