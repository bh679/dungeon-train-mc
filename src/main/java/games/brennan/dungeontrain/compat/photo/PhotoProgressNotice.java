package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.EnchiridionAdvancements;
import games.brennan.dungeontrain.mixin.PlayerAdvancementsVisibleAccessor;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * The chat line a shot gets for each camera advancement it moved along without earning it — a new
 * entry in a collection, one more photo towards a count tier, a new biome towards a biome tier.
 *
 * <p>The camera advancements are hidden, so a line goes out only once the advancement is on the
 * player's advancement screen ({@link PlayerAdvancementsVisibleAccessor}): "Photo counted toward
 * [Nature Documentary] (4/12)". Progress towards one still hidden says nothing. The target is given
 * only where the advancement's album shows one: a count tier's album lists just the photos kept
 * ({@code client.EarnedPhotos}), so its line is "[Field Guide] (12)".
 * An advancement the shot earned outright says nothing here; vanilla already announces it.</p>
 *
 * <p>{@link PhotoAdvancementEvents} runs a shot's triggers {@link #during} a notice; the trackers note
 * what they moved, and the lines go out once the triggers are done.</p>
 */
final class PhotoProgressNotice {

    static final String KEY_NAMED = "chat.dungeontrain.photo_progress.named";
    static final String KEY_NAMED_COUNT = "chat.dungeontrain.photo_progress.named_count";

    /** {@code count} of {@code max} towards an advancement. */
    record Progress(int count, int max) {}

    private static final ThreadLocal<Map<ResourceLocation, Progress>> OPEN = new ThreadLocal<>();

    private PhotoProgressNotice() {}

    /** Run a shot's {@code triggers}, then tell {@code player} what they moved along. */
    static void during(ServerPlayer player, Runnable triggers) {
        Map<ResourceLocation, Progress> previous = OPEN.get();
        Map<ResourceLocation, Progress> noted = new LinkedHashMap<>();
        OPEN.set(noted);
        try {
            triggers.run();
        } finally {
            if (previous == null) OPEN.remove();
            else OPEN.set(previous);
        }
        noted.forEach((id, progress) -> send(player, id, progress));
    }

    /** True while a shot's triggers run. */
    static boolean isOpen() {
        return OPEN.get() != null;
    }

    /** Note {@code progress} towards {@code id}; a later note for the same advancement replaces it. */
    static void note(ResourceLocation id, Progress progress) {
        Map<ResourceLocation, Progress> noted = OPEN.get();
        if (noted != null && id != null && progress != null) noted.put(id, progress);
    }

    /** A collection that just gained an entry: its criteria earned so far, of all of them. */
    static void noteCollection(ServerPlayer player, AdvancementHolder advancement) {
        if (!isOpen() || !isCamera(advancement.id()) || advancement.value().criteria().size() <= 1) return;
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(advancement);
        int earned = 0;
        for (String ignored : progress.getCompletedCriteria()) earned++;
        note(advancement.id(), new Progress(earned, advancement.value().criteria().size()));
    }

    /**
     * Note {@code count} towards the first of {@code tiers} (paths, lowest first) not yet earned. Call it
     * before the tally's trigger runs, so a tier the shot earns is the one noted — and then left to vanilla.
     */
    static void noteNextTier(ServerPlayer player, List<String> tiers,
                             ToIntFunction<String> countOf, ToIntFunction<String> targetOf) {
        if (!isOpen()) return;
        String tier = nextTier(tiers, path -> SelfieChallengeHints.isEarned(player, id(path)));
        if (tier == null) return;
        int target = targetOf.applyAsInt(tier);
        if (target > 0) note(id(tier), new Progress(countOf.applyAsInt(tier), target));
    }

    /** The first of {@code tiers} not {@code earned}, or {@code null} when all are. */
    static String nextTier(List<String> tiers, java.util.function.Predicate<String> earned) {
        for (String tier : tiers) {
            if (!earned.test(tier)) return tier;
        }
        return null;
    }

    /** The line for a revealed advancement, {@code name} its chat link; the target only if {@code showMax}. */
    static Component line(Component name, Progress progress, boolean showMax) {
        return showMax
                ? Component.translatable(KEY_NAMED, name, progress.count(), progress.max())
                : Component.translatable(KEY_NAMED_COUNT, name, progress.count());
    }

    /** Whether {@code path}'s album shows a target: every album but a count tier's. */
    static boolean showsMax(String path) {
        return !EnchiridionAdvancements.isCountAlbum(EnchiridionAdvancements.albumOf(path));
    }

    private static void send(ServerPlayer player, ResourceLocation id, Progress progress) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        AdvancementHolder holder = server.getAdvancements().get(id);
        if (holder == null || holder.value().display().isEmpty()) return;
        if (player.getAdvancements().getOrStartProgress(holder).isDone() || !isVisible(player, holder)) return;
        holder.value().name().ifPresent(name -> SelfieChallengeHints.tell(player, line(name, progress, showsMax(id.getPath()))));
    }

    private static boolean isVisible(ServerPlayer player, AdvancementHolder holder) {
        return ((PlayerAdvancementsVisibleAccessor) player.getAdvancements()).dungeontrain$getVisible().contains(holder);
    }

    private static boolean isCamera(ResourceLocation id) {
        return DungeonTrain.MOD_ID.equals(id.getNamespace()) && id.getPath().startsWith(EnchiridionAdvancements.PATH_PREFIX);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path);
    }
}
