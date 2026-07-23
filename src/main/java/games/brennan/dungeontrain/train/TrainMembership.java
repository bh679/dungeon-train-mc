package games.brennan.dungeontrain.train;

import net.minecraft.world.entity.Entity;

import java.util.Collection;
import java.util.OptionalInt;

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
        return entity != null && hasContentsTag(entity.getTags());
    }

    /**
     * Pure tag-matching core, separated from the {@link Entity} adapter so it
     * can be unit-tested without a live entity. Package-private.
     *
     * @return true when any tag starts with
     *         {@link CarriageContentsPlacer#DT_CONTENTS_TAG_PREFIX}.
     */
    static boolean hasContentsTag(Collection<String> tags) {
        if (tags == null) return false;
        for (String tag : tags) {
            if (tag != null && tag.startsWith(CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX)) return true;
        }
        return false;
    }

    /**
     * The carriage position index (pIdx) baked into the entity's contents tag
     * at spawn, or empty for entities without one (or with a malformed tag).
     * This is the entity's own spatial frame — deliberately independent of any
     * player's travelled progress.
     */
    public static OptionalInt carriageIndexOf(Entity entity) {
        if (entity == null) return OptionalInt.empty();
        return carriageIndexOf(entity.getTags());
    }

    /** Pure tag-parsing core, package-private for unit tests. */
    static OptionalInt carriageIndexOf(Collection<String> tags) {
        if (tags == null) return OptionalInt.empty();
        for (String tag : tags) {
            if (tag == null || !tag.startsWith(CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX)) continue;
            try {
                return OptionalInt.of(Integer.parseInt(
                    tag.substring(CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX.length())));
            } catch (NumberFormatException ignored) {
                // malformed suffix — keep scanning; another tag may parse
            }
        }
        return OptionalInt.empty();
    }
}
