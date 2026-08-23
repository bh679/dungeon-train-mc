package games.brennan.dungeontrain.client.reset;

import games.brennan.dungeontrain.advancement.GlobalAchievementStore;
import games.brennan.dungeontrain.advancement.GlobalBookBurnStats;
import games.brennan.dungeontrain.advancement.GlobalNarrativeProgress;
import games.brennan.dungeontrain.advancement.GlobalPlayerStats;
import games.brennan.dungeontrain.compat.EnderChestResetBridge;
import games.brennan.dungeontrain.compat.PlayerMobLivesReset;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.narrative.PlayerPlayedMarker;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.util.Locale;
import java.util.UUID;

/**
 * One piece of the cross-world Dungeon Train profile, as the reset flow sees it: is it there, and
 * how is it removed. Each constant owns nothing itself — it delegates to the store that already
 * knows its own path and cache — so this enum stays a menu of what "your progress" is made of.
 *
 * <p>Two of these belong to sibling mods rather than Dungeon Train — the Ender Chest stash and the
 * past-lives history — because they are cross-world player progress just the same, and leaving them
 * behind is visible on camera. Both go through a {@code compat} class, never touched directly.</p>
 *
 * <p>Everything here lives <em>outside</em> any world save, which is exactly why a new world does
 * not feel like a new start: {@code AchievementEvents} replays {@link #ADVANCEMENTS} onto every
 * world on login, and the stats keep counting. Ordered as the confirm screen lists them.</p>
 *
 * @see ProfileWipe
 */
public enum ProfileItem {

    /** The advancement sidecar replayed onto every world on login. */
    ADVANCEMENTS,
    /** Lifetime totals behind the death screen's "all your lives" page and the milestone triggers. */
    STATS,
    /** Unread-book burn counter (its own file beside {@link #STATS}). */
    BOOKS_BURNED,
    /** Which narrative letters and story variants this install has already read. */
    NARRATIVE,
    /** "Has been welcomed before", world counters, and the dimension-welcome playlist. */
    PLAYER_MARKER,
    /** The persistent Ender Chest stash, every game-mode and difficulty slot of it. */
    ENDER_CHEST,
    /** PlayerMob's record of past lives — what later runs raise Echoes and PlayerMobs from. */
    PAST_LIVES,
    /** One-time client flags: advancements hint, dev popup, Free Play confirm, last survey answer. */
    CLIENT_FLAGS;

    /** Screen label, e.g. {@code …video_tools.reset.item.advancements}. */
    public Component label() {
        return Component.translatable(
            "gui.dungeontrain.video_tools.reset.item." + name().toLowerCase(Locale.ROOT));
    }

    /** True when this item has something to remove — drives both the list and "nothing to reset". */
    public boolean present(UUID uuid) {
        return switch (this) {
            case ADVANCEMENTS -> Files.isRegularFile(GlobalAchievementStore.file(uuid));
            case STATS -> Files.isRegularFile(GlobalPlayerStats.file(uuid));
            case BOOKS_BURNED -> Files.isRegularFile(GlobalBookBurnStats.file(uuid));
            case NARRATIVE -> Files.isRegularFile(GlobalNarrativeProgress.file());
            case PLAYER_MARKER -> PlayerPlayedMarker.hasPlayed(uuid);
            case ENDER_CHEST -> EnderChestResetBridge.hasStash(uuid);
            case PAST_LIVES -> PlayerMobLivesReset.hasLives(uuid);
            case CLIENT_FLAGS -> ClientDisplayConfig.hasFirstRunFlagsSet();
        };
    }

    /**
     * Remove it. Each store drops its in-memory cache as part of this, so a later flush in the same
     * JVM cannot write the old state back over the deletion.
     *
     * @return true when something was actually removed
     */
    public boolean delete(UUID uuid) throws Exception {
        return switch (this) {
            case ADVANCEMENTS -> GlobalAchievementStore.deleteFor(uuid);
            case STATS -> GlobalPlayerStats.deleteFor(uuid);
            case BOOKS_BURNED -> GlobalBookBurnStats.deleteFor(uuid);
            case NARRATIVE -> GlobalNarrativeProgress.deleteAll();
            case PLAYER_MARKER -> PlayerPlayedMarker.deleteFor(uuid);
            case ENDER_CHEST -> EnderChestResetBridge.clear(uuid);
            case PAST_LIVES -> PlayerMobLivesReset.clear(uuid);
            case CLIENT_FLAGS -> {
                ClientDisplayConfig.resetFirstRunFlags();
                yield true;
            }
        };
    }
}
