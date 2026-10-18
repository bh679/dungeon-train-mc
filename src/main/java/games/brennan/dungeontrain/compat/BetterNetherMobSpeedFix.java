package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Gives BetterNether's jungle skeleton a skeleton's walking speed.
 *
 * <p>BetterNether registers {@code betternether:jungle_skeleton} with
 * {@code Monster.createMonsterAttributes()} rather than {@code AbstractSkeleton.createAttributes()}, so its
 * movement speed falls back to the attribute default of 0.7 — nearly three times a vanilla skeleton's 0.25.
 * Two in three spawn with a sword, so they read as zombies sprinting at the player. Overriding the base here
 * fixes every one, from DT's enemy rooms and BetterNether's own Nether-jungle spawns alike. Remove once
 * BetterNether registers the proper skeleton attributes.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class BetterNetherMobSpeedFix {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final ResourceLocation JUNGLE_SKELETON_ID = ResourceLocation.parse("betternether:jungle_skeleton");

    /** Vanilla {@code AbstractSkeleton.createAttributes()} movement speed. */
    static final double SKELETON_MOVEMENT_SPEED = 0.25;

    private BetterNetherMobSpeedFix() {}

    @SubscribeEvent
    @SuppressWarnings("unchecked")
    public static void onModifyAttributes(EntityAttributeModificationEvent event) {
        Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(JUNGLE_SKELETON_ID);
        if (type.isEmpty()) {
            LOGGER.warn("[DungeonTrain] {} not registered; jungle skeleton speed fix skipped", JUNGLE_SKELETON_ID);
            return;
        }
        EntityType<? extends LivingEntity> living = (EntityType<? extends LivingEntity>) type.get();
        if (!event.has(living, Attributes.MOVEMENT_SPEED)) {
            LOGGER.warn("[DungeonTrain] {} has no movement speed attribute; speed fix skipped", JUNGLE_SKELETON_ID);
            return;
        }
        event.add(living, Attributes.MOVEMENT_SPEED, SKELETON_MOVEMENT_SPEED);
    }
}
