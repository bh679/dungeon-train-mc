package games.brennan.dungeontrain.compat.photo;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.EnchiridionAdvancements;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mob photo collections that list the same mob share its photo. Family Photo and Skeleton Crew are
 * subsets of Most Wanted, each criterion named for the mob and keyed on the same subject, so a new
 * shot already ticks every list it is in. A tick earned before a list gained that mob (the Luminous
 * Monsters variants), or restored onto one list from another world, is copied across by name here —
 * at login only once the client has said it holds a photo for it ({@link #onAlbumReport}). A copied
 * tick brings no photo; the album borrows Most Wanted's ({@code EarnedPhotos#visibleEntries}).
 */
public final class SharedPhotoCriteria {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The collections that share criteria by name — {@code SharedPhotoCriteriaTest} pins their subjects. */
    static final List<String> GROUP = EnchiridionAdvancements.SHARED_PHOTO_LISTS;

    /**
     * Player persistent-data flag: this world's one-time revoke has run. Before the login copy waited for
     * photos, it finished Family Photo and Skeleton Crew with mobs no photo was ever taken of.
     */
    static final String REVOKED_KEY = "dungeontrain_photo_ports_revoked";

    /** Set while this class awards a tick, so the award's own progress event doesn't copy it on again. */
    private static boolean copying = false;

    private SharedPhotoCriteria() {}

    /**
     * Login, once the client has reported which mobs it holds photos of ({@code PhotoAlbumReportPacket}):
     * the one-time revoke, then bring each list level with the others where a photo backs the tick.
     *
     * @param photos list path → the mobs with a photo in that list's own album
     */
    public static void onAlbumReport(ServerPlayer player, Map<String, Set<String>> photos) {
        if (!player.getPersistentData().getBoolean(REVOKED_KEY)) {
            revokePhotoless(player, photos);
            player.getPersistentData().putBoolean(REVOKED_KEY, true);
        }
        shareAll(player, photos);
    }

    /** A finished Family Photo or Skeleton Crew with a mob no photo shows loses that mob's tick. */
    private static void revokePhotoless(ServerPlayer player, Map<String, Set<String>> photos) {
        for (AdvancementHolder holder : members(player)) {
            AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
            List<String> revoke = photolessTicks(holder.id().getPath(), progress.isDone(), ticked(progress), photos);
            for (String criterion : revoke) player.getAdvancements().revoke(holder, criterion);
            if (!revoke.isEmpty()) {
                LOGGER.info("[DungeonTrain] {} was finished without photos for {}: revoked {}",
                        holder.id(), player.getName().getString(), revoke);
            }
        }
    }

    private static void shareAll(ServerPlayer player, Map<String, Set<String>> photos) {
        for (AdvancementHolder holder : members(player)) {
            for (String criterion : ticked(player.getAdvancements().getOrStartProgress(holder))) {
                for (AdvancementHolder to : members(player)) {
                    if (hasPhoto(photos, to.id().getPath(), criterion)) copy(player, to, holder, criterion);
                }
            }
        }
    }

    /**
     * {@code criterion} was just ticked on {@code from}: tick it on the other lists in the group that name it.
     * Only that one: a shot's photo is current while this runs and shows that criterion's mob, so copying
     * the rest of {@code from}'s ticks here would give each of them this shot's photo.
     */
    static void share(ServerPlayer player, AdvancementHolder from, String criterion) {
        if (copying || !inGroup(from.id())) return;
        for (AdvancementHolder to : members(player)) copy(player, to, from, criterion);
    }

    private static void copy(ServerPlayer player, AdvancementHolder to, AdvancementHolder from, String criterion) {
        if (to.id().equals(from.id())) return;
        // award() refuses a criterion the list doesn't name or already holds.
        if (!to.value().criteria().containsKey(criterion)) return;
        copying = true;
        try {
            player.getAdvancements().award(to, criterion);
        } finally {
            copying = false;
        }
    }

    /** Does {@code list} have a photo of {@code criterion}'s mob — its own, or the one it borrows? Pure. */
    static boolean hasPhoto(Map<String, ? extends Collection<String>> photos, String list, String criterion) {
        if (holds(photos, list, criterion)) return true;
        String fallback = EnchiridionAdvancements.photoFallbackAlbum(list);
        return fallback != null && holds(photos, fallback, criterion);
    }

    private static boolean holds(Map<String, ? extends Collection<String>> photos, String list, String criterion) {
        Collection<String> ids = photos.get(list);
        return ids != null && ids.contains(criterion);
    }

    /**
     * The ticks the one-time revoke takes from {@code list}: none unless it is a subset list that is finished,
     * then each tick without a photo. A list with every photo keeps everything. Pure.
     */
    static List<String> photolessTicks(String list, boolean done, Collection<String> ticked,
                                       Map<String, ? extends Collection<String>> photos) {
        if (!done || EnchiridionAdvancements.photoFallbackAlbum(list) == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String criterion : ticked) {
            if (!hasPhoto(photos, list, criterion)) out.add(criterion);
        }
        return out;
    }

    static boolean inGroup(ResourceLocation id) {
        return DungeonTrain.MOD_ID.equals(id.getNamespace()) && GROUP.contains(id.getPath());
    }

    private static List<String> ticked(AdvancementProgress progress) {
        List<String> out = new ArrayList<>();
        for (String criterion : progress.getCompletedCriteria()) out.add(criterion);
        return out;
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
