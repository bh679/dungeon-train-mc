package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.sable.SableEntityCarry;
import games.brennan.dungeontrain.world.PendingPets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What happens to a player's pets when the carriage they are on unloads or goes away for good.
 *
 * <h2>The rule</h2>
 * A pet told to <b>sit</b> stays in its carriage. It is swept with the carriage's contents when the
 * carriage unloads and comes back with it, and in a drifting (shared) carriage it is part of the build
 * the player leaves behind: it rides the carriage's upload to the relay. Any pet that is <b>not</b>
 * sitting goes to its owner instead of being left behind on a carriage that is unloading.
 *
 * <p>A carriage deleted for good takes its despawn snapshot with it, so sitting pets frozen in that
 * snapshot are handed to their owner too — unless they sat in a drifting carriage the relay holds, in
 * which case they have left with it. A live pet whose owner is offline stays where it is; a frozen one
 * waits in {@link PendingPets} until the owner is back.</p>
 *
 * <p>Pure decisions ({@link #fate}, {@link #mayUpload}) are split from the world-touching code so
 * they test without Minecraft.</p>
 */
public final class CarriagePets {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Vanilla's save keys for an owned entity's owner and its sit order ({@code TamableAnimal}). */
    private static final String NBT_OWNER = "Owner";
    private static final String NBT_SITTING = "Sitting";

    /** What becomes of one pet when its carriage is removed. */
    enum Fate {
        /** It left with the drifting carriage — the local copy goes. */
        DRIFTS,
        /** Teleported (or respawned) beside its owner. */
        SENT_HOME,
        /** Owner offline: left as it is. */
        LEFT
    }

    private CarriagePets() {}

    // ---- pure decisions ----

    /** @param leftWithCarriage sitting inside a drifting carriage the relay holds */
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

    /** An owned pet ordered to sit — it stays with its carriage. */
    public static boolean isSittingPet(Entity entity) {
        return ownerOf(entity) != null && isSitting(entity);
    }

    /** True when the entity, or anything riding it, is an owned pet that is not sitting. */
    public static boolean isStandingPet(Entity entity) {
        for (Entity part : entity.getSelfAndPassengers().toList()) {
            if (!mayUpload(ownerOf(part) != null, isSitting(part))) return true;
        }
        return false;
    }

    // ---- carriage unloading (the contents-despawn sweep) ----

    /**
     * The carriage is about to unload: nobody has been near it for a while. Standing pets go to their
     * owner. Sitting pets stay — the sweep that follows freezes them with the carriage — and a drifting
     * carriage holding one is queued for an upload so the build the relay holds has them in it.
     */
    public static void onCarriageUnloading(ServerLevel level, ManagedShip ship) {
        List<Entity> pets = petsAboard(level, ship);
        if (pets.isEmpty()) return;
        boolean anySitting = false;
        for (Entity pet : pets) {
            if (isSitting(pet)) {
                anySitting = true;
                continue;
            }
            ServerPlayer owner = onlineOwner(level, ownerOf(pet));
            if (owner != null) sendHome(pet, owner, "an unloading carriage");
        }
        if (!anySitting || !SharedCarriageRegistry.hasSubLevel(ship.subLevelId())) return;
        for (SharedCarriageRegistry.Instance inst : SharedCarriageRegistry.bySubLevel(ship.subLevelId())) {
            for (Entity pet : pets) {
                if (isSitting(pet) && CarriageEntitySnapshot.inFootprint(ship, inst.shipyardOrigin, inst.dims, pet)) {
                    inst.enqueue(inst.shipyardOrigin);
                    break;
                }
            }
        }
    }

    // ---- carriage removal ----

    /**
     * Sitting pets inside the shared carriage {@code inst}, which drift with it. When there are any,
     * the carriage is queued for a final full capture so the upload actually carries them.
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

    /** A drifting carriage's footprint in shipyard space, for pets that only exist in a snapshot. */
    public record DriftFootprint(BlockPos origin, CarriageDims dims) {
        public static DriftFootprint of(SharedCarriageRegistry.Instance inst) {
            return inst.relayId() == null ? null : new DriftFootprint(inst.shipyardOrigin, inst.dims);
        }

        boolean contains(double x, double y, double z) {
            return CarriageEntitySnapshot.inFootprint(origin, dims, x - origin.getX(), y - origin.getY(), z - origin.getZ());
        }
    }

    /**
     * Settle every live pet aboard {@code ship} before it is deleted. MUST run on the server thread,
     * before {@code Shipyard.delete}. A no-op for a ship that is no longer resident.
     *
     * @param drifted pets that left with a drifting carriage's final upload this pass
     */
    public static void settleBeforeRemoval(ServerLevel level, ManagedShip ship, Set<Entity> drifted) {
        for (Entity pet : petsAboard(level, ship)) {
            try {
                ServerPlayer owner = onlineOwner(level, ownerOf(pet));
                switch (fate(drifted.contains(pet), owner != null)) {
                    case DRIFTS -> {
                        pet.discard();
                        LOGGER.info("[DungeonTrain] Sitting pet {} left with its drifting carriage", typeId(pet));
                    }
                    case SENT_HOME -> sendHome(pet, owner, "a removed carriage");
                    case LEFT -> LOGGER.info("[DungeonTrain] Pet {} on a removed carriage left in place: owner offline",
                            typeId(pet));
                }
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] Pet {} on a removed carriage could not be settled: {}",
                        typeId(pet), t.toString());
            }
        }
    }

    /**
     * Hand back the pets frozen in a carriage's despawn snapshot, which is about to be deleted with
     * the carriage. Call BEFORE {@link ContentsSnapshotStore#delete}.
     *
     * @param drifting footprints of this carriage's drifting slots the relay holds
     */
    public static void rescueFromSnapshot(ServerLevel level, UUID subLevelId, List<DriftFootprint> drifting) {
        ContentsEntitySnapshot snapshot = ContentsSnapshotStore.read(level, subLevelId);
        if (snapshot == null) return;
        for (CompoundTag stored : snapshot.entities()) {
            if (!stored.hasUUID(NBT_OWNER)) continue;
            String id = stored.getString("id");
            try {
                boolean leftWithCarriage = stored.getBoolean(NBT_SITTING) && inAny(drifting, stored);
                ServerPlayer owner = onlineOwner(level, stored.getUUID(NBT_OWNER));
                switch (fate(leftWithCarriage, owner != null)) {
                    case DRIFTS -> LOGGER.info("[DungeonTrain] Sitting pet {} left with its drifting carriage", id);
                    case SENT_HOME -> respawnBeside(stored, owner);
                    case LEFT -> {
                        PendingPets.get(level).add(stored.getUUID(NBT_OWNER), stored);
                        LOGGER.info("[DungeonTrain] Pet {} from a removed carriage is waiting for its offline owner", id);
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] Pet {} in a removed carriage's snapshot could not be handed back: {}",
                        id, t.toString());
            }
        }
    }

    private static boolean inAny(List<DriftFootprint> drifting, CompoundTag stored) {
        ListTag pos = stored.getList(ContentsEntitySnapshot.KEY_SHIP_POS, Tag.TAG_DOUBLE);
        if (pos.size() != 3) return false;
        for (DriftFootprint f : drifting) {
            if (f.contains(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2))) return true;
        }
        return false;
    }

    // ---- moving pets ----

    /**
     * A pet catching up with an owner who rides a carriage lands on the deck at their feet.
     *
     * <p>Vanilla's follow-teleport picks a spot near the owner with ground under it, but it reads
     * only the main level, where a carriage deck is air. The one "ground" it finds there is the track
     * bed under the carriage, so the pet lands in the floor (2026-10-26: a wolf landed in the floor
     * that way, was moved off the side of a bridge and fell to its death).</p>
     *
     * @return true when the pet was moved and vanilla's teleport must not run
     */
    public static boolean followOntoDeck(TamableAnimal pet) {
        if (pet.level().isClientSide()) return false;
        if (!(pet.getOwner() instanceof ServerPlayer owner) || owner.level() != pet.level()) return false;
        if (!ridingDeck(owner)) return false;
        SableEntityCarry.shed(pet);
        pet.setDeltaMovement(Vec3.ZERO);
        pet.resetFallDistance();
        pet.moveTo(owner.getX(), owner.getY(), owner.getZ(), pet.getYRot(), pet.getXRot());
        pet.getNavigation().stop();
        return true;
    }

    /** Standing on a carriage deck: their feet are on something a main-level block scan can't see. */
    static boolean ridingDeck(ServerPlayer owner) {
        return !owner.isSpectator() && SableEntityCarry.carrier(owner) != null;
    }

    static void sendHome(Entity pet, ServerPlayer owner, String from) {
        SableEntityCarry.shed(pet);
        pet.setDeltaMovement(Vec3.ZERO);
        pet.resetFallDistance();
        if (pet instanceof Mob mob) mob.getNavigation().stop();
        // Vanilla's own follow-teleport first: it picks a free spot beside a grounded owner.
        Vec3 before = pet.position();
        if (pet instanceof TamableAnimal tamable && pet.level() == owner.level() && owner.onGround()) {
            tamable.tryToTeleportToOwner();
            if (!pet.position().equals(before)) {
                logSentHome(typeId(pet), owner, from);
                return;
            }
        }
        Vec3 at = landingNear(owner);
        if (pet.teleportTo(owner.serverLevel(), at.x, at.y, at.z, Set.of(), pet.getYRot(), pet.getXRot())) {
            logSentHome(typeId(pet), owner, from);
        } else {
            LOGGER.warn("[DungeonTrain] Pet {} could not be sent to owner {} from {}",
                    typeId(pet), owner.getGameProfile().getName(), from);
        }
    }

    private static void logSentHome(String type, ServerPlayer owner, String from) {
        LOGGER.info("[DungeonTrain] Pet {} sent to owner {} from {}", type, owner.getGameProfile().getName(), from);
    }

    /** How far below a flying owner to look for ground a pet can land on. */
    static final int LANDING_SCAN_BLOCKS = 96;

    /**
     * Where a pet lands beside {@code owner}: at their feet when they stand on something, otherwise on
     * the first ground below them. A pet dropped at a flying owner's position falls to its death.
     */
    static Vec3 landingNear(ServerPlayer owner) {
        if (owner.onGround() && !owner.isSpectator()) return owner.position();
        // A carriage deck is not a world block, so the scan below would fall straight through it to
        // the track bed. An owner riding one is standing on it: put the pet at their feet.
        if (ridingDeck(owner)) return owner.position();
        ServerLevel level = owner.serverLevel();
        BlockPos.MutableBlockPos p = owner.blockPosition().mutable();
        int floor = Math.max(level.getMinBuildHeight(), p.getY() - LANDING_SCAN_BLOCKS);
        for (; p.getY() > floor; p.move(0, -1, 0)) {
            BlockPos below = p.below();
            if (level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)
                    && level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                    && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) {
                return Vec3.atBottomCenterOf(p);
            }
        }
        return owner.position();
    }

    /** Respawn a saved pet beside its owner — the contents-despawn restore idiom, at the owner. */
    public static void respawnBeside(CompoundTag stored, ServerPlayer owner) {
        ServerLevel to = owner.serverLevel();
        CompoundTag nbt = stored.copy();
        nbt.remove(ContentsEntitySnapshot.KEY_SHIP_POS);
        nbt.remove(ContentsEntitySnapshot.KEY_SHIP_YROT);
        Vec3 at = landingNear(owner);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(at.x));
        pos.add(DoubleTag.valueOf(at.y));
        pos.add(DoubleTag.valueOf(at.z));
        nbt.put("Pos", pos);
        Entity pet = EntityType.loadEntityRecursive(nbt, to, e -> e);
        if (pet == null) {
            LOGGER.warn("[DungeonTrain] Pet {} from a removed carriage could not be loaded", nbt.getString("id"));
            return;
        }
        pet.moveTo(at.x, at.y, at.z, owner.getYRot(), 0.0f);
        pet.setDeltaMovement(Vec3.ZERO);
        pet.resetFallDistance();
        if (!to.tryAddFreshEntityWithPassengers(pet)) {
            // The same UUID is already loaded — re-identify, as the despawn restore does.
            pet.getSelfAndPassengers().forEach(e -> e.setUUID(UUID.randomUUID()));
            if (!to.tryAddFreshEntityWithPassengers(pet)) {
                LOGGER.warn("[DungeonTrain] Pet {} from a removed carriage was rejected by the level", typeId(pet));
                return;
            }
        }
        LOGGER.info("[DungeonTrain] Pet {} sent to owner {} from a removed carriage's snapshot",
                typeId(pet), owner.getGameProfile().getName());
    }

    @Nullable
    private static ServerPlayer onlineOwner(ServerLevel level, @Nullable UUID ownerId) {
        return ownerId == null ? null : level.getServer().getPlayerList().getPlayer(ownerId);
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
