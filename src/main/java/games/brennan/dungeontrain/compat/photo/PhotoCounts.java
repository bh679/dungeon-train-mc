package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * How many photos a player has taken of animals, hostile mobs and passengers — the tallies behind
 * The Enchiridion's 50 / 200 / 500 photo tiers. A photo counts once per category, however many of
 * that kind are in frame; what counts as which is {@link PhotoSubjectTally#of}, the same reading the
 * relay's photo leaderboards use.
 *
 * <p>Kept in the player's persistent data, beside the photographed-biome list.</p>
 */
final class PhotoCounts {

    /** Player persistent-data key: a compound of category → photos counted. */
    static final String KEY = "dungeontrain_photo_counts";

    static final String ANIMAL = "animal";
    static final String HOSTILE = "hostile";
    static final String PASSENGER = "passenger";

    private PhotoCounts() {}

    /** The categories a shot with these subjects counts towards. */
    static List<String> categories(PhotoSubjectTally.Subjects subjects) {
        List<String> out = new ArrayList<>(3);
        if (subjects.animals()) out.add(ANIMAL);
        if (subjects.hostile()) out.add(HOSTILE);
        if (subjects.passengers()) out.add(PASSENGER);
        return out;
    }

    /** Count one shot towards each of its categories and fire the tier trigger for each. */
    static void record(ServerPlayer player, PhotoSubjectTally.Subjects subjects) {
        List<String> categories = categories(subjects);
        if (categories.isEmpty()) return;
        CompoundTag counts = player.getPersistentData().getCompound(KEY).copy();
        for (String category : categories) {
            int count = counts.getInt(category) + 1;
            counts.putInt(category, count);
            ModAdvancementTriggers.PHOTO_COUNT.get().trigger(player, category, count);
        }
        player.getPersistentData().put(KEY, counts);
    }
}
