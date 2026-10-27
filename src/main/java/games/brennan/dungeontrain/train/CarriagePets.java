package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.sable.SableEntityCarry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What happens to a player's pets when the carriage they are on goes away for good.
 *
 * <h2>The rule</h2>
 * A pet told to <b>sit</b> in a drifting (shared) carriage stays in it: it is part of the build the
 * player leaves behind, so it rides the carriage's upload to the relay and is gone from this world
 * with it. Any other pet aboard a carriage that is being removed — standing, or sitting in a carriage
 * that does not drift — is teleported to its owner instead of being stranded in mid-air or deleted
 * along with a despawn snapshot. A pet whose owner is offline is left exactly where it is.
 *
 * <p>The same rule decides what a drifting carriage may upload: only sitting pets. A standing pet
 * belongs with its owner, and capturing it is how a player found two of their old wolves following
 * them in a brand-new world.</p>
 *
 * <p>Pure decisions ({@link #fate}, {@link #mayUpload}) are split from the world-touching code so
 * they test without Minecraft.</p>
 */
public final class CarriagePets {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** What becomes of one pet when its carriage is removed. */
    enum Fate {
        /** It left with the drifting carriage's upload — the local copy is discarded. */
        DRIFTS,
        /** Teleported to its owner. */
        SENT_HOME,
        /** Owner offline: left where it stands, as before. */
        LEFT
    }

    private CarriagePets() {}

    // ---- pure decisions ----

    /**
     * @param leftWithCarriage sitting, inside a drifting carriage, and that carriage's final upload
     *                         carried it this pass
     */
    static Fate fate(boolean leftWithCarriage, boolean ownerOnline) {
        if (leftWithCarriage) return Fate.DRIFTS;
        return ownerOnline ? Fate.SENT_HOME : Fate.LEFT;
    }

    /** Whether an entity may go into a drifting carriage's upload, as far as ownership goes. */
    static boolean mayUpload(boolean owned, boolean sitting) {
        return !owned || sitting;
    }

    // ---- entity reads ----

    /** The owner of a tamed entity, or null for anything wild or unownable. */
    @Nullable
    public static UUID ownerOf(Entity entity) {
        return entity instanceof OwnableEntity pet ? pet.getOwnerUUID() : null;
    }

    /** Ordered to sit. Owned mobs that cannot sit (tamed horses, llamas) never are. */
    public static boolean isSitting(Entity entity) {
        return entity instanceof TamableAnimal tamable && tamable.isOrderedToSit();
    }

    /** True for an owned pet that is not sitting — it, or anything riding it, stays out of uploads. */
    public static boolean isStandingPet(Entity entity) {
        for (Entity part : entity.getSelfAndPassengers().toList()) {
            if (!mayUpload(ownerOf(part) != null, isSitting(part))) return true;
        }
        return false;
    }

    // ---- teardown ----

    /**
     * Sitting pets inside the shared carriage {@code inst}, which would drift with it. When there are
     * any, the carriage is queued for a final full capture so the upload actually carries them —
     * otherwise a carriage with no pending block edits returns its lease without one.
     */
    public static List<Entity> sittingDrifters(ServerLevel level, ManagedShip ship,
                                               SharedCarriageRegistry.Instance inst) {
        List<Entity> out = new ArrayList<>();
        if (inst.relayId() == null) return out; // never uploaded, so there is nothing to drift in
        for (Entity pet : petsAboard(level, ship)) {
            if (isSitting(pet) && CarriageEntitySnapshot.inFootprint(ship, inst.shipyardOrigin, inst.dims, pet)) {
                out.add(pet);
            }
        }
        if (!out.isEmpty()) inst.enqueue(inst.shipyardOrigin);
        return out;
    }

    /**
     * Settle every pet aboard {@code ship} before it is deleted. MUST run on the server thread,
     * before {@code Shipyard.delete}, while the ship is still resident.
     *
     * @param drifted pets that left with a drifting carriage's final upload this pass
     */
    public static void settleBeforeRemoval(ServerLevel level, ManagedShip ship, Set<Entity> drifted) {
        for (Entity pet : petsAboard(level, ship)) {
            try {
                settle(level, pet, drifted.contains(pet));
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] Pet {} on a removed carriage could not be settled: {}",
                        typeId(pet), t.toString());
            }
        }
    }

    private static void settle(ServerLevel level, Entity pet, boolean leftWithCarriage) {
        UUID ownerId = ownerOf(pet);
        ServerPlayer owner = ownerId == null ? null : level.getServer().getPlayerList().getPlayer(ownerId);
        switch (fate(leftWithCarriage, owner != null)) {
            case DRIFTS -> {
                pet.discard();
                LOGGER.info("[DungeonTrain] Sitting pet {} left with its drifting carriage", typeId(pet));
            }
            case SENT_HOME -> sendHome(pet, owner);
            case LEFT -> LOGGER.info("[DungeonTrain] Pet {} on a removed carriage left in place — owner offline",
                    typeId(pet));
        }
    }

    private static void sendHome(Entity pet, ServerPlayer owner) {
        SableEntityCarry.shed(pet);
        pet.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        pet.resetFallDistance();
        if (pet instanceof Mob mob) mob.getNavigation().stop();
        boolean moved = pet.teleportTo(owner.serverLevel(), owner.getX(), owner.getY(), owner.getZ(),
                Set.of(), pet.getYRot(), pet.getXRot());
        LOGGER.info("[DungeonTrain] Pet {} sent to owner {} from a removed carriage ({})",
                typeId(pet), owner.getGameProfile().getName(), moved ? "ok" : "refused");
    }

    /** Owned pets on the ship — the same aboard test the contents-despawn sweep uses. */
    private static List<Entity> petsAboard(ServerLevel level, ManagedShip ship) {
        // A culled ship's pets sit in unloaded chunks: nothing to move, and its box is stale.
        if (!ship.isResident()) return List.of();
        var b = ship.worldAABB();
        AABB box = new AABB(
                b.minX() - 1, b.minY() - 1, b.minZ() - 1,
                b.maxX() + 1, b.maxY() + 2, b.maxZ() + 1);
        UUID subLevelId = ship.subLevelId();
        return level.getEntities((Entity) null, box, e -> e.isAlive()
                && !e.isPassenger()
                && ownerOf(e) != null
                && ContentsDespawnController.isAboard(isCarriedBy(e, subLevelId),
                    e.getX(), e.getY(), e.getZ(),
                    b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()));
    }

    private static boolean isCarriedBy(Entity entity, UUID subLevelId) {
        SubLevel carrier = SableEntityCarry.carrier(entity);
        return carrier != null && !carrier.isRemoved() && carrier.getUniqueId().equals(subLevelId);
    }

    private static String typeId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }
}
