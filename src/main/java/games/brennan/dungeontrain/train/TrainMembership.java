package games.brennan.dungeontrain.train;

import net.minecraft.world.entity.Entity;

/**
 * Single source of truth for "is this entity on the train?".
 *
 * <p>Carriage contents and on-train mob-group spawns are stamped at spawn with
 * a tag {@code DT_CONTENTS_TAG_PREFIX + pIdx} (see
 * {@link CarriageContentsPlacer#contentsTagFor(int)}), which persists across
 * save/load. Membership is therefore a cheap tag-prefix scan — deliberately not
 * a Sable spatial query — because this is also consulted on every mob spawn in
 * the world via Adventure Item Names' mob-name gate (see
 * {@code DungeonTrain} setup), so it must stay allocation-free and fast.
 */
public final class TrainMembership {

    private TrainMembership() {}

    /**
     * @return true when {@code entity} carries a Dungeon Train contents tag —
     *         i.e. it was spawned as carriage contents or as part of an on-train
     *         mob group. False for {@code null} and for ordinary world entities.
     */
    public static boolean isOnTrain(Entity entity) {
        if (entity == null) return false;
        for (String tag : entity.getTags()) {
            if (tag.startsWith(CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX)) return true;
        }
        return false;
    }
}
