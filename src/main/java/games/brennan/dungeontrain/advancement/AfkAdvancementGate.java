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
 * <p>"AFK" is {@link PlayerActivityTracker#isAfk}: paused, mouse idle or input idle. A refused
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
 *   <li>{@link #EXEMPT} — one-shot advancements earned from inside a pause screen, or that arrive
 *       on their own whatever the player is doing. Every pause screen counts as AFK, and the
 *       client reports the screen closing a tick after the packet that earns these, so without
 *       the exemption they could never be earned at all.</li>
 * </ul>
 */
public final class AfkAdvancementGate {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Advancements the AFK rule never refuses. Only one-shots belong here: anything that re-fires
     * (scans, thresholds) recovers on its own once the player is back, which is the rule working.
     */
    static final Set<ResourceLocation> EXEMPT = Stream.of(
        // Earned on a pause screen, so always PAUSED when the grant lands.
        "dungeon_train/changed_engine_volume",   // Sound Check: the volume sliders are all in pause menus
        "dungeon_train/the_enchiridion",         // sent as the held-book screen closes
        "dungeon_train/signed_shared_book",      // Sign button on the book-and-quill screen
        "dungeon_train/submitted_death_note",    // same signing path
        "dungeon_train/submitted_love_note",
        "dungeon_train/deathnote_self_target",
        "dungeon_train/lovenote_self_target",
        "dungeon_train/burned_leaderboard_book", // sent as the starting book closes; the book burns
        "dungeon_train/burned_stat_book",
        "dungeon_train/contained_loop",          // judged at death, incl. Abandon run from the pause menu
        // Arrive on their own, whenever they arrive.
        "dungeon_train/the_creator_answers",     // the developer's reply lands on the relay's schedule
        "enchiridion/called_it",                 // takeover vote result, usually while watching the stream
        "enchiridion/candid",                    // picking up a photo a PlayerMob tossed you
        "enchiridion/found_footage"              // photo viewer's close packet beats the Esc key report
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
