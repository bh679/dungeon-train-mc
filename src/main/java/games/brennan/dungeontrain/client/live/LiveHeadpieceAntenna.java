package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * The live headpiece's aerial: its tip lights up for whatever the wearer is looking at.
 *
 * <p>Vanilla's {@code CustomHeadLayer} resolves a head item's model with the <em>wearer</em> as
 * the predicate's entity, so this is a plain {@link ItemProperties} predicate with no networking:
 * every client already knows each nearby player's position and look, ray-casts for them locally
 * and lands on the same colour. The predicate value is an {@link AntennaTarget}; the model's
 * overrides swap the tip swatch.</p>
 *
 * <p>One ray-cast per wearer per client tick, cached by entity id — the head layer renders the hat
 * once per frame per pass, and the look only changes between ticks anyway. In a hand, on the
 * ground or in a slot the tip stays unlit.</p>
 */
public final class LiveHeadpieceAntenna {

    public static final ResourceLocation PROPERTY =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "antenna_target");

    /** How far the aerial "sees", in blocks. */
    static final double REACH = 32.0;
    /** Readings for more wearers than this get swept each time a new one is added. */
    private static final int SWEEP_ABOVE = 64;

    private record Reading(long tick, AntennaTarget target) {}

    private static final Map<Integer, Reading> READINGS = new HashMap<>();

    private LiveHeadpieceAntenna() {}

    /** Registers the predicate; call from client setup's {@code enqueueWork}. */
    public static void register() {
        ItemProperties.register(ModItems.LIVE_HEADPIECE.get(), PROPERTY,
            (stack, level, entity, seed) -> targetFor(stack, level, entity).propertyValue());
    }

    /** Forget every wearer, on logout. */
    public static void reset() {
        READINGS.clear();
    }

    static AntennaTarget targetFor(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity entity) {
        if (level == null || entity == null || entity.getItemBySlot(EquipmentSlot.HEAD) != stack) {
            return AntennaTarget.OFF;
        }
        long tick = level.getGameTime();
        Reading cached = READINGS.get(entity.getId());
        if (cached != null && cached.tick() == tick) {
            return cached.target();
        }
        AntennaTarget target = look(entity);
        if (READINGS.size() > SWEEP_ABOVE) {
            READINGS.values().removeIf(reading -> reading.tick() != tick);
        }
        READINGS.put(entity.getId(), new Reading(tick, target));
        return target;
    }

    /** Blocks first, then any living thing in front of the block hit; nearest wins. */
    static AntennaTarget look(LivingEntity wearer) {
        HitResult blockHit = wearer.pick(REACH, 1.0f, false);
        boolean hitBlock = blockHit.getType() == HitResult.Type.BLOCK;
        Vec3 eye = wearer.getEyePosition(1.0f);
        Vec3 view = wearer.getViewVector(1.0f);
        double range = hitBlock ? Math.sqrt(blockHit.getLocation().distanceToSqr(eye)) : REACH;
        Vec3 end = eye.add(view.scale(range));
        AABB search = wearer.getBoundingBox().expandTowards(view.scale(range)).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
            wearer.level(), wearer, eye, end, search, candidate -> isLookable(candidate, wearer));
        boolean hitEntity = entityHit != null;
        boolean hostile = hitEntity && entityHit.getEntity() instanceof Enemy;
        return AntennaTarget.classify(hitEntity, hostile, hitBlock);
    }

    private static boolean isLookable(Entity candidate, LivingEntity wearer) {
        return candidate != wearer
            && candidate instanceof LivingEntity living
            && living.isAlive()
            && !living.isSpectator();
    }
}
