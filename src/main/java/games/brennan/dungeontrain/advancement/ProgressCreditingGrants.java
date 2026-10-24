package games.brennan.dungeontrain.advancement;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.Criterion;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which advancement grants prove the player is getting somewhere, for
 * {@link games.brennan.dungeontrain.event.PlayerActivityTracker#creditProgress}.
 *
 * <p>Earning an advancement counts as movement — but only one the player's own hands earned. A
 * grant is judged by its criterion's <em>trigger type</em>, against an allowlist of triggers that
 * fire from a deliberate act: a kill, a placed block, a befriended PlayerMob, a chest opened, an
 * editor action, a read or burned book. Everything else is denied by default, which covers the
 * triggers that fire on their own while the train carries a still player along — train time, run
 * and lifetime distance, biomes visited, carriages counted, players and Echoes encountered, roof
 * runs, code grants, {@code minecraft:impossible} and vanilla's location scans — and
 * {@code dungeontrain:gameplay_action}, which mixes hand-driven actions with 15-minute timers,
 * carriage counts and band arrivals and so cannot be told apart by type. Those hand-driven actions
 * already stamp the idle clocks through the click or screen input that caused them; what they do
 * not do is restart the progress window, and that is the conservative side to err on.</p>
 *
 * <p>Photo triggers are not listed either: the camera shot itself credits progress directly
 * ({@code PhotoAdvancementEvents.onFrameAdded}), before any of them run. Display-less advancements
 * (recipes) and the {@link AfkAdvancementGate#EXEMPT passive one-shots} never count.</p>
 *
 * <p>{@code ProgressCreditingGrantsTest} pins that every trigger type the shipped advancements use
 * is named on one list or the other, so a new trigger forces the decision.</p>
 */
public final class ProgressCreditingGrants {

    /** Trigger types that fire from a deliberate act of the player's. */
    static final Set<ResourceLocation> PLAYER_ACTION_TRIGGERS = Stream.of(
        "dungeontrain:editor_action",
        "dungeontrain:unique_chests_opened",
        "dungeontrain:story_set_completed",
        "dungeontrain:random_books_read",
        "dungeontrain:starting_books_read",
        "dungeontrain:books_burned_unread",
        "dungeontrain:gave_playermob_unrequited",
        "dungeontrain:befriended_playermob",
        "dungeontrain:named_playermob",
        "dungeontrain:pushed_playermob_off_train",
        "dungeontrain:defended_playermob",
        "dungeontrain:befriended_echo",
        "dungeontrain:killed_echo",
        "minecraft:player_killed_entity",
        "minecraft:player_hurt_entity",
        "minecraft:placed_block",
        "minecraft:item_used_on_block",
        "minecraft:consume_item",
        "minecraft:enchanted_item",
        "minecraft:brewed_potion",
        "minecraft:bred_animals",
        "minecraft:tame_animal",
        "minecraft:fishing_rod_hooked",
        "minecraft:shot_crossbow",
        "minecraft:villager_trade",
        "minecraft:cured_zombie_villager",
        "minecraft:construct_beacon",
        "minecraft:filled_bucket"
    ).map(ResourceLocation::parse).collect(Collectors.toUnmodifiableSet());

    /**
     * Trigger types the shipped Dungeon Train advancements use that fire on their own, or that mix
     * passive and hand-driven grants. Named so the test can insist every used trigger is decided.
     */
    static final Set<ResourceLocation> PASSIVE_OR_MIXED_TRIGGERS = Stream.of(
        "dungeontrain:gameplay_action",
        "dungeontrain:code_granted",
        "dungeontrain:train_time",
        "dungeontrain:run_train_time",
        "dungeontrain:run_distance",
        "dungeontrain:lifetime_distance",
        "dungeontrain:biomes_visited",
        "dungeontrain:carts_in_run",
        "dungeontrain:carts_both_directions",
        "dungeontrain:roof_run_groups",
        "dungeontrain:proximity_on_train",
        "dungeontrain:encountered_players",
        "dungeontrain:encountered_echo",
        "dungeontrain:echo_feat",
        "dungeontrain:multiplayer_join",
        "dungeontrain:photo_subject",
        "dungeontrain:photo_biomes",
        "dungeontrain:photo_tribute",
        "dungeontrain:photo_count",
        "minecraft:impossible",
        "minecraft:inventory_changed"
    ).map(ResourceLocation::parse).collect(Collectors.toUnmodifiableSet());

    private ProgressCreditingGrants() {}

    /** Does granting {@code criterion} of {@code holder} prove the player is getting somewhere? */
    public static boolean proves(AdvancementHolder holder, String criterion) {
        Criterion<?> c = holder.value().criteria().get(criterion);
        ResourceLocation trigger = c == null ? null : BuiltInRegistries.TRIGGER_TYPES.getKey(c.trigger());
        return proves(trigger, holder.value().display().isPresent(), AfkAdvancementGate.isExempt(holder.id()));
    }

    /** The pure rule: a displayed, non-exempt advancement whose criterion fired from a listed trigger. */
    static boolean proves(@Nullable ResourceLocation triggerId, boolean displayed, boolean exempt) {
        if (!displayed || exempt || triggerId == null) return false;
        return PLAYER_ACTION_TRIGGERS.contains(triggerId);
    }
}
