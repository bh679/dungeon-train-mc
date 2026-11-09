package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.EnchiridionAdvancements;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Mob photo collections that list the same mob share its photo. Family Photo and Skeleton Crew are
 * subsets of Most Wanted, each criterion named for the mob and keyed on the same subject, so a new
 * shot already ticks every list it is in. A tick earned before a list gained that mob (the Luminous
 * Monsters variants), or restored onto one list from another world, is copied across by name here.
 */
final class SharedPhotoCriteria {

    /** The collections that share criteria by name — {@code SharedPhotoCriteriaTest} pins their subjects. */
    static final List<String> GROUP = List.of(
            EnchiridionAdvancements.PATH_PREFIX + "most_wanted",
            EnchiridionAdvancements.PATH_PREFIX + "family_photo",
            EnchiridionAdvancements.PATH_PREFIX + "skeleton_crew");

    private SharedPhotoCriteria() {}

    /** Login: bring every list in the group level with the others. */
    static void shareAll(ServerPlayer player) {
        for (AdvancementHolder holder : members(player)) share(player, holder);
    }

    /** Copy {@code from}'s ticked mobs onto the other lists in the group that name them. */
    static void share(ServerPlayer player, AdvancementHolder from) {
        if (!inGroup(from.id())) return;
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(from);
        List<String> held = new ArrayList<>();
        for (String criterion : progress.getCompletedCriteria()) held.add(criterion);
        if (held.isEmpty()) return;
        for (AdvancementHolder to : members(player)) {
            if (to.id().equals(from.id())) continue;
            for (String criterion : held) {
                // award() refuses a criterion the list doesn't name or already holds.
                if (to.value().criteria().containsKey(criterion)) player.getAdvancements().award(to, criterion);
            }
        }
    }

    static boolean inGroup(ResourceLocation id) {
        return DungeonTrain.MOD_ID.equals(id.getNamespace()) && GROUP.contains(id.getPath());
    }

    private static List<AdvancementHolder> members(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return List.of();
        List<AdvancementHolder> found = new ArrayList<>(GROUP.size());
        for (String path : GROUP) {
            AdvancementHolder holder = server.getAdvancements()
                    .get(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path));
            if (holder != null) found.add(holder);
        }
        return found;
    }
}
