package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.PlayerActivityTracker;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * No advancements while AFK. Consulted by {@code PlayerAdvancementsAfkGateMixin} at the head of
 * {@code PlayerAdvancements.award}, the one call every grant goes through — vanilla triggers, Dungeon
 * Train's own triggers and the code-driven awards alike.
 *
 * <p>"AFK" is {@link PlayerActivityTracker#isAfk}, the same rule that stops time on the train:
 * paused (unless recently clicking or typing on that screen, within a cap), mouse idle, input idle,
 * or not getting anywhere (fewer than three carriages in ten minutes, outside dimensional
 * carriages). A refused
 * criterion is simply not granted — no event, no toast, no relay post. Event-style triggers fired
 * while away are lost; threshold triggers (distance, train time, biomes visited, ...) fire again and
 * grant once the player is back.</p>
 *
 * <p>Three kinds of award always pass:</p>
 * <ul>
 *   <li>{@link AdvancementProfiles#isReplaying()} — the login / profile-swap replay of advancements
 *       the player already earned.</li>
 *   <li>Advancements with no display — recipe unlocks and technical nodes. They are not on the
 *       advancements screen (the same line {@link AdvancementProfiles}' persist policy draws), and
 *       refusing them would silently lose recipes for items picked up while idle.</li>
 *   <li>{@link #EXEMPT} — one-shot advancements that arrive on their own, whatever the player is
 *       doing at that moment. Ones earned on a pause screen need no entry: the click that earns
 *       them marks the player present, and the client reports a screen closing before anything
 *       sent as it closes.</li>
 * </ul>
 */
public final class AfkAdvancementGate {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Advancements the AFK rule never refuses. Only one-shots belong here: anything that re-fires
     * (scans, thresholds) recovers on its own once the player is back, which is the rule working.
     */
    static final Set<ResourceLocation> EXEMPT = Stream.of(
        "dungeon_train/the_creator_answers",     // the developer's reply lands on the relay's schedule
        "enchiridion/called_it",                 // takeover vote result, usually while watching the stream
        "enchiridion/candid"                     // picking up a photo a PlayerMob tossed you
    ).map(path -> ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path))
        .collect(Collectors.toUnmodifiableSet());

    private AfkAdvancementGate() {}

    /** Should this grant be refused? {@code player} is null before the tracker owner is attached. */
    public static boolean shouldBlock(@Nullable ServerPlayer player, AdvancementHolder holder, String criterion) {
        if (player == null) return false;
        if (AdvancementProfiles.isReplaying()) return false;
        if (holder.value().display().isEmpty()) return false;
        if (isExempt(holder.id())) return false;
        if (!PlayerActivityTracker.isAfk(player)) return false;
        LOGGER.debug("[DungeonTrain] Refused {} / {} for {}: AFK ({})", holder.id(), criterion,
            player.getName().getString(), PlayerActivityTracker.reason(player));
        return true;
    }

    /** Is this advancement one the AFK rule leaves alone? */
    static boolean isExempt(ResourceLocation id) {
        return EXEMPT.contains(id);
    }
}
