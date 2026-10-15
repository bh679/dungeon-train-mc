package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.player.DifficultyPartition;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Carries out a relay-sent, one-time <b>promote</b>: copies (or moves) advancements from a player's
 * pre-split history into another difficulty's profile.
 *
 * <p>The per-difficulty split put everything earned before it on Normal, so a player who did most of
 * their runs on Hard found their Hard profile empty. The pre-split history is already on their disk —
 * {@code achievements/pre-split/<uuid>.json}, kept by {@link AdvancementSplitBackup} — so the relay
 * sends only the instruction ({@code AdvancementPromoteClient}); the player picks the advancements and
 * this class writes the files.</p>
 *
 * <p><b>Title screen only.</b> No world is running there, so nothing else is reading the profiles, and
 * the logout reconcile can't strip ids the world tree doesn't hold (the same reason
 * {@code ProfileWipe} works there). Path-based throughout so it can be tested without the game.</p>
 *
 * <p><b>One time.</b> Every promote id the player answered is appended to {@value #USED_FILE} before the
 * relay is told, so a lost ack can never offer the same promote twice.</p>
 */
public final class AdvancementPromotion {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Promote ids this install has already answered (applied or declined), one per line. */
    static final String USED_FILE = ".promotions-used";

    /** What the copy/move choice is about: whether the source's Normal profile keeps the id. */
    public enum Mode {
        /** Added to the target; Normal keeps it. The player may still choose Move per advancement. */
        COPY,
        /** Added to the target and removed from Normal. */
        MOVE;

        /** The wire name ({@code "copy"} / {@code "move"}), or null for anything else. */
        public static Mode parse(String wire) {
            if ("copy".equals(wire)) return COPY;
            if ("move".equals(wire)) return MOVE;
            return null;
        }
    }

    /** What {@link #apply} wrote, for the ack and the log. */
    public record Applied(int added, int removed) {}

    private AdvancementPromotion() {}

    /** {@code <gameDir>/dungeontrain/achievements/} — the live profile folder. */
    public static Path liveDir() {
        return PlayerDataPaths.dir(PlayerDataPaths.ACHIEVEMENTS);
    }

    /**
     * The advancements a promote into {@code target} can offer: the pre-split backup (or, on an install
     * that never had one, the Normal profile), minus the shared Train Editor tab — every difficulty
     * already sees that — and minus whatever {@code target} already holds. Insertion order is kept, so
     * the list reads in the order they were earned.
     */
    public static List<ResourceLocation> candidates(Path dir, UUID uuid, String target) {
        Set<ResourceLocation> already = GlobalAchievementStore.readFile(profile(dir, uuid, target));
        List<ResourceLocation> out = new ArrayList<>();
        for (ResourceLocation id : source(dir, uuid)) {
            if (GlobalAchievementStore.isShared(id) || already.contains(id)) continue;
            out.add(id);
        }
        return List.copyOf(out);
    }

    /** The pre-split backup when there is one, else the Normal profile. */
    static Set<ResourceLocation> source(Path dir, UUID uuid) {
        Path backup = dir.resolve(AdvancementSplitBackup.COPY_DIR).resolve(uuid + ".json");
        if (Files.isRegularFile(backup)) return GlobalAchievementStore.readFile(backup);
        return GlobalAchievementStore.readFile(profile(dir, uuid, DifficultyPartition.LEGACY_KEY));
    }

    /**
     * Add {@code copyIds} and {@code moveIds} to {@code target}, then strike {@code moveIds} from the
     * Normal profile. Moving into Normal itself is just a copy. The backup is never touched, so a
     * promote can always be redone by hand from it.
     */
    public static Applied apply(Path dir, UUID uuid, String target,
                                Collection<ResourceLocation> copyIds, Collection<ResourceLocation> moveIds) {
        Path targetFile = profile(dir, uuid, target);
        Set<ResourceLocation> into = new LinkedHashSet<>(GlobalAchievementStore.readFile(targetFile));
        int before = into.size();
        into.addAll(copyIds);
        into.addAll(moveIds);
        int added = into.size() - before;
        if (added > 0) GlobalAchievementStore.writeAtomic(targetFile, into);

        int removed = 0;
        if (!DifficultyPartition.LEGACY_KEY.equals(target) && !moveIds.isEmpty()) {
            Path normalFile = profile(dir, uuid, DifficultyPartition.LEGACY_KEY);
            Set<ResourceLocation> normal = new LinkedHashSet<>(GlobalAchievementStore.readFile(normalFile));
            int had = normal.size();
            normal.removeAll(moveIds);
            removed = had - normal.size();
            if (removed > 0) GlobalAchievementStore.writeAtomic(normalFile, normal);
        }
        LOGGER.info("[DungeonTrain] Advancement promote: {} added to {}, {} moved off Normal",
            added, target, removed);
        return new Applied(added, removed);
    }

    /** Has this install already answered promote {@code id}? */
    public static boolean isUsed(Path dir, String id) {
        return usedIds(dir).contains(id);
    }

    /** Record promote {@code id} as answered. Returns false when the marker couldn't be written. */
    public static boolean markUsed(Path dir, String id) {
        if (isUsed(dir, id)) return true;
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(USED_FILE), id + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Advancement promote: couldn't record {} as used: {}", id, e.toString());
            return false;
        }
    }

    static Set<String> usedIds(Path dir) {
        Path file = dir.resolve(USED_FILE);
        if (!Files.isRegularFile(file)) return Set.of();
        try {
            Set<String> out = new LinkedHashSet<>();
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) out.add(line.trim());
            }
            return out;
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Advancement promote: couldn't read {}: {}", file, e.toString());
            return Set.of();
        }
    }

    private static Path profile(Path dir, UUID uuid, String partition) {
        return dir.resolve(GlobalAchievementStore.fileName(uuid, partition));
    }
}
