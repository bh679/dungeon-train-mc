package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.event.PlayerActivityTracker;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import javax.annotation.Nullable;

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
 * <p>Two kinds of award restore rather than earn, and always pass:</p>
 * <ul>
 *   <li>{@link AdvancementProfiles#isReplaying()} — the login / profile-swap replay of advancements
 *       the player already earned.</li>
 *   <li>Advancements with no display — recipe unlocks and technical nodes. They are not on the
 *       advancements screen (the same line {@link AdvancementProfiles}' persist policy draws), and
 *       refusing them would silently lose recipes for items picked up while idle.</li>
 * </ul>
 */
public final class AfkAdvancementGate {

    private static final Logger LOGGER = LogUtils.getLogger();

    private AfkAdvancementGate() {}

    /** Should this grant be refused? {@code player} is null before the tracker owner is attached. */
    public static boolean shouldBlock(@Nullable ServerPlayer player, AdvancementHolder holder, String criterion) {
        if (player == null) return false;
        if (AdvancementProfiles.isReplaying()) return false;
        if (holder.value().display().isEmpty()) return false;
        if (!PlayerActivityTracker.isAfk(player)) return false;
        LOGGER.debug("[DungeonTrain] Refused {} / {} for {}: AFK ({})", holder.id(), criterion,
            player.getName().getString(), PlayerActivityTracker.reason(player));
        return true;
    }
}
