package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.editor.CarriageContentsGroupStore;
import games.brennan.dungeontrain.editor.CarriageContentsVariantBlocks;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.VariantState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.util.Optional;

/**
 * Which carriage contents are "enemy carts" — interiors whose mob-variant sidecar can roll a
 * hostile ({@link MobCategory#MONSTER}) mob. The no-hostiles opening stretch keeps these out of the
 * contents pool ({@link CarriageContentsRegistry}) rather than stamping them and deleting their mobs.
 *
 * <p>A group parent counts as an enemy cart if it or any of its members can roll a hostile, since
 * picking the parent may resolve to any member. Reads the sidecars through
 * {@link CarriageContentsVariantBlocks#loadFor}'s own cache, so editor edits are seen at once.</p>
 */
public final class HostileContents {

    private HostileContents() {}

    /** True when contents {@code id} (or, for a group parent, any member) can spawn a hostile mob. */
    public static boolean canSpawnHostile(String id) {
        if (leafCanSpawnHostile(id)) return true;
        Optional<CarriageContentsGroup> group = CarriageContentsGroupStore.get(id);
        if (group.isEmpty()) return false;
        for (CarriageContentsGroup.Member m : group.get().members()) {
            if (leafCanSpawnHostile(m.id())) return true;
        }
        return false;
    }

    /** {@link #canSpawnHostile} for one template, ignoring its group. */
    static boolean leafCanSpawnHostile(String id) {
        Optional<CarriageContents> contents = CarriageContentsRegistry.find(id);
        if (contents.isEmpty()) return false;
        CarriageContentsVariantBlocks sidecar = CarriageContentsVariantBlocks.loadFor(contents.get(), null);
        for (CarriageVariantBlocks.Entry entry : sidecar.entries()) {
            for (VariantState s : entry.states()) {
                if (s.isMob() && isHostileType(s.entityId().toString())) return true;
            }
        }
        return false;
    }

    private static boolean isHostileType(String entityId) {
        return EntityType.byString(entityId)
            .map(t -> t.getCategory() == MobCategory.MONSTER)
            .orElse(false);
    }
}
