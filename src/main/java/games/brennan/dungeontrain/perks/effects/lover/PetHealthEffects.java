package games.brennan.dungeontrain.perks.effects.lover;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.AnimalTameEvent;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Pet Health (Lover, tier 2): every pet the player owns carries a max-health modifier worth
 * {@link PetHealthMath#bonusHealth(int)} for the owner's active rank.
 *
 * <p>Applied when a pet is tamed ({@link AnimalTameEvent} for wolves, cats, parrots and ocelots;
 * horses through {@code AbstractHorsePerkTameMixin}, since they never raise the event), when an
 * owned entity enters a level with its owner online (chunk load, login), and when the owner's rank
 * changes ({@link #onRankChanged}). The modifier is keyed by one id, so re-applying replaces
 * rather than stacks, and a rank of 0 removes it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PetHealthEffects {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation MODIFIER_ID =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "perk_pet_health");

    private PetHealthEffects() {}

    @SubscribeEvent
    public static void onAnimalTame(AnimalTameEvent event) {
        if (event.getTamer() instanceof ServerPlayer owner) {
            // Fired BEFORE tame() sets the owner, so apply against the tamer directly.
            apply(event.getAnimal(), Perks.rank(owner, LoverPerkIds.PET_HEALTH));
        }
    }

    /** Called by the horse tame mixin after {@code tameWithName} succeeded. */
    public static void onTamed(LivingEntity pet, Player owner) {
        if (owner instanceof ServerPlayer serverOwner) {
            apply(pet, Perks.rank(serverOwner, LoverPerkIds.PET_HEALTH));
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof OwnableEntity ownable) || !(event.getEntity() instanceof LivingEntity pet)) return;
        UUID ownerId = ownable.getOwnerUUID();
        if (ownerId == null) return;
        Player owner = event.getLevel().getServer() == null ? null
            : event.getLevel().getServer().getPlayerList().getPlayer(ownerId);
        if (owner == null) return;
        apply(pet, Perks.rank(owner, LoverPerkIds.PET_HEALTH));
    }

    /** Re-applies the bonus to every loaded pet the player owns; rank changes are rare so a scan is fine. */
    public static void onRankChanged(ServerPlayer owner) {
        MinecraftServer server = owner.getServer();
        if (server == null) return;
        int rank = Perks.rank(owner, LoverPerkIds.PET_HEALTH);
        UUID ownerId = owner.getUUID();
        int touched = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof OwnableEntity ownable && entity instanceof LivingEntity pet
                        && ownerId.equals(ownable.getOwnerUUID())) {
                    apply(pet, rank);
                    touched++;
                }
            }
        }
        LOGGER.debug("[DT-Perks] pet-health rank {} re-applied to {} pets of {}", rank, touched, owner.getGameProfile().getName());
    }

    /** Replaces the Pet Health modifier on {@code pet} with the one for {@code rank}; heals any gain. */
    public static void apply(LivingEntity pet, int rank) {
        AttributeInstance maxHealth = pet.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth == null) return;
        AttributeModifier current = maxHealth.getModifier(MODIFIER_ID);
        double before = current == null ? 0.0 : current.amount();
        double after = PetHealthMath.bonusHealth(rank);
        if (before == after) return;
        maxHealth.removeModifier(MODIFIER_ID);
        if (after > 0.0) {
            maxHealth.addPermanentModifier(new AttributeModifier(MODIFIER_ID, after, AttributeModifier.Operation.ADD_VALUE));
        }
        if (after > before) {
            pet.heal((float) (after - before));
        } else if (pet.getHealth() > pet.getMaxHealth()) {
            pet.setHealth(pet.getMaxHealth());
        }
    }
}
